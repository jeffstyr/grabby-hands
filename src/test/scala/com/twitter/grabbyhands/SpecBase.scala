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

import java.util.logging.{FileHandler,Level,Logger,SimpleFormatter}
import org.specs.Specification

class SpecBase extends Specification {
  val log = Logger.getLogger("grabbyhands")
  val handler = new FileHandler("grabbyhands.log", true)
  handler.setFormatter(new SimpleFormatter())
  handler.setLevel(Level.ALL)
  log.setLevel(Level.ALL)
  log.addHandler(handler)

  var grab: GrabbyHands = _
  var config: Config = _
  val queues = Array("grabby_test1", "grabby_test2", "grabby_test3")
  val queue = queues(0)
  val host = "localhost"
  val port = 22133
  val hostPort = host + ":" + port
  val shortMessageMax = 256

  def defaults(): Config = {
    config = Config.factory(Array(host + ":" + port))
    config.maxMessageBytes = shortMessageMax
    config.addQueue(queues(0))
    config
  }

  def ctor(): GrabbyHands = {
    grab = new GrabbyHands(config)
    grab
  }
}