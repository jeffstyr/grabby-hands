/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.grabbyhands

import java.lang.StringBuilder
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.{BlockingQueue, TimeUnit}
import java.util.logging.Level

// ConnectionRecv owns the socket, ConnectionSend only writes into the socket if available.
protected class ConnectionRecv(
  queue: Queue,
  connectionName: String,
  server: String
) extends ConnectionBase(queue,
                         connectionName,
                         server) {
  val recvQueue = queue.recvQueue

  protected val request = ByteBuffer.wrap((
    "get " + queueName + "/t=" + grabbyHands.config.kestrelReadTimeoutMs + "\r\n").getBytes)
  val response = ByteBuffer.allocate(grabbyHands.config.maxMessageBytes + 100)
  protected val expectEnd = ByteBuffer.wrap("END\r\n".getBytes)
  expectEnd.rewind()
  protected val expectHeader = ByteBuffer.wrap(("VALUE " + queueName + " 0 ").getBytes)

  def run2(): Boolean = {
    if (log.isLoggable(Level.FINEST)) log.finest(connectionName + " read")

    // Send request
    request.rewind()
    if (!writeBuffer(request)) {
      return false
    }

    // Read either END or VALUE...END.
    // Read until at least expectEnd bytes arrive.
    response.clear()
    setLongReadTimeout()
    val readStatus = readBuffer(expectEnd.capacity, response)
    setNormalReadTimeout()
    if (!readStatus) {
      return false
    }

    log.finest(connectionName + " XXX read raw |" +
               new String(response.array, 0, response.position) + "|")

    val positionSave = response.position
    response.flip()

    //XXX
    //XXX
    log.finest("A %d %d %d %d".format(response.position, response.limit,
                                      expectEnd.position, expectEnd.limit))
    //XXX
    //XXX

    // May have END\r\n indicating null read, or may have payload
    if (log.isLoggable(Level.FINEST))
      log.finest(connectionName + " read response " + response.position)

    // Bytebuffer.equals() ignores position
    if (response.equals(expectEnd)) {
      // Empty read, timeout
      if (log.isLoggable(Level.FINEST)) log.finest(connectionName + " kestrel get timeout")
      queueCounters.kestrelGetTimeouts.incrementAndGet()
      return true
    }
    response.position(positionSave)
    response.limit(response.capacity())

    log.finest(connectionName + " position1 " + response.position)

    if (response.position < expectHeader.capacity + 1) {
      // Read enough for expected header and at least a 1 digit length.
      if (!readBuffer(expectHeader.capacity + 1, response)) {
        return false
      }
    }
    if (log.isLoggable(Level.FINEST))
      log.finest(connectionName + " read bytes " + response.position)

    // Determine length of message.
    // At this point here, this is where you throw up your hands about how bad
    // the memcache protocol is.

    // Move position to the first byte of the length, the position just after expectHeader
    response.flip()
    response.position(expectHeader.capacity)

    var found = false
    while (!found && response.hasRemaining) {
      if (response.get() == '\n') {
        found = true
      } else if (!response.hasRemaining()) {
        log.finest(connectionName + " read additional header bytes")
        // Read more, in the unlikely case that the entire header didn't come over at once
        if (haltLatch.getCount() == 0) {
          return false
        }
        if (!selectRead()) {
          return false
        }
        // Position is already at the limit, should be able to just append.
        response.limit(response.capacity)
        socket.read(response)
      }
    }

    if (!response.hasRemaining) {
      log.fine(connectionName + " protocol error on header reading length")
      serverCounters.protocolError.incrementAndGet()
      queueCounters.protocolError.incrementAndGet()
      return false
    }
    // response.position now points to the start of the payload (if ready, else where it will be.)

    // Parse the payload length, ignoring 2 trailing chars \r\n
    val payloadLength = Integer.parseInt(new String(
      response.array, expectHeader.capacity, response.position - (2 + expectHeader.capacity)))
    log.finest(connectionName + " payload length " + payloadLength)

    // Ensure that entire payload plus an expectEnd can be read
    val payloadStart = response.position
    val payloadEnd = payloadStart + payloadLength
    val footerStart = payloadEnd + 2   // \r\n padding
    val footerEnd = footerStart + expectEnd.capacity
    val needed = footerEnd - response.limit


    log.finest(connectionName + " XXX start=" + payloadStart + " end=" + payloadEnd +
               " footerStart=" + footerStart + " footerEnd=" + footerEnd + " needed=" + needed)

    //XXX
    //XXX
    log.finest("B %d %d %d".format(response.position, response.limit, response.capacity))
    //XXX
    //XXX

    if (needed > 0) {
      log.finest(connectionName + " XXX need to read more payload " + needed)
      response.position(response.limit)
      response.limit(response.capacity)
      //XXX
      //XXX
      log.finest("C %d %d %d".format(response.position, response.limit, response.capacity))
      //XXX
      //XXX
      if (!readBuffer(footerEnd, response)) {
        //XXX
        //XXX
        log.finest("D %d %d %d".format(response.position, response.limit, response.capacity))
        //XXX
        //XXX
        return false
      }
      response.flip()
    }
    //XXX
    //XXX
    log.finest("E %d %d %d".format(response.position, response.limit, response.capacity))
    //XXX
    //XXX


    // Create a new ByteBuffer pointing to only the payload that shares the same array --
    // avoiding a copy. Embargo this buffer until the footer validates.
    val payload = ByteBuffer.allocate(payloadLength)
    response.position(payloadStart)
    response.get(payload.array)

    log.finest(connectionName + " XXX payload |" +
               new String(payload.array, 0, payload.capacity) + "|")

    // Consume the END footer
    log.finest(connectionName + " XXX raw footer |" +
               new String(response.array, footerStart, expectEnd.capacity) + "|")
    response.position(footerStart)
    expectEnd.rewind()
    //XXX
    //XXX
    log.finest("F %d %d %d".format(response.position, response.limit, response.capacity))
    //XXX
    //XXX
    if (response != expectEnd) {
      log.fine(connectionName + " protocol error on footer")
      serverCounters.protocolError.incrementAndGet()
      queueCounters.protocolError.incrementAndGet()
      return false
    }

    serverCounters.messagesRecv.incrementAndGet()
    serverCounters.bytesRecv.addAndGet(payloadLength)
    queueCounters.messagesRecv.incrementAndGet()
    queueCounters.bytesRecv.addAndGet(payloadLength)

    recvQueue.offer(payload, 999999, TimeUnit.HOURS)
    if (log.isLoggable(Level.FINEST)) log.finest(connectionName + " message read")

    true
  }

  // readTimeoutMs is assumed to be the saftey factor accounting for typical glitches, etc.
  def setLongReadTimeout() {
    readTimeoutMs = grabbyHands.config.kestrelReadTimeoutMs + grabbyHands.config.readTimeoutMs
  }

  def setNormalReadTimeout() {
    readTimeoutMs = grabbyHands.config.readTimeoutMs
  }
}