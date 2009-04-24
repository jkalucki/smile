/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
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

package net.lag.smile.kestrel

import net.lag.configgy.Config
import net.lag.smile.MemcacheClient


/*
 * KestrelClientInterface implementation that uses smile. This is the default.
 */
class SmileKestrelClient(config: Config) extends KestrelClientInterface {
  var client = {
    if (config.getString("distribution").isEmpty) {
      config.setString("distribution", "sequential")
    }
    // FIXME: Due to Configgy bug, must refetch from Configgy to get locally set values.
    MemcacheClient.create(config)
  }

  def poll(key: String): Option[String] = client.get(key)
  def pollData(key: String): Option[Array[Byte]] = client.getData(key)
  def put(key: String, value: String): Unit = client.set(key, value)
  def putData(key: String, value: Array[Byte]): Unit = client.setData(key, value)
  def shutdown() = client.shutdown
}