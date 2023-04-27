/*
 * Copyright 2020-2023 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect

import cats.effect.unsafe.IORuntime
import cats.syntax.all._

import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey._
import scala.concurrent.duration._

class SelectorPollerSpec extends BaseSpec {

  def mkPipe: Resource[IO, Pipe] =
    Resource
      .eval(IO.poller[SelectorPoller].map(_.get))
      .flatMap { poller =>
        Resource.make(IO(poller.provider.openPipe())) { pipe =>
          IO(pipe.sink().close()).guarantee(IO(pipe.source().close()))
        }
      }
      .evalTap { pipe =>
        IO {
          pipe.sink().configureBlocking(false)
          pipe.source().configureBlocking(false)
        }
      }

  "SelectorPoller" should {

    "notify read-ready events" in real {
      mkPipe.use { pipe =>
        for {
          poller <- IO.poller[SelectorPoller].map(_.get)
          buf <- IO(ByteBuffer.allocate(4))
          _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3)))).background.surround {
            poller.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
          }
          _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(42)))).background.surround {
            poller.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
          }
        } yield buf.array().toList must be_==(List[Byte](1, 2, 3, 42))
      }
    }

    "setup multiple callbacks" in real {
      mkPipe.use { pipe =>
        for {
          poller <- IO.poller[SelectorPoller].map(_.get)
          _ <- poller.select(pipe.source, OP_READ).parReplicateA_(10) <&
            IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3))))
        } yield ok
      }
    }

    "works after blocking" in real {
      mkPipe.use { pipe =>
        for {
          poller <- IO.poller[SelectorPoller].map(_.get)
          _ <- IO.blocking(())
          _ <- poller.select(pipe.sink, OP_WRITE)
        } yield ok
      }
    }

    "gracefully handles illegal ops" in real {
      mkPipe.use { pipe =>
        IO.poller[SelectorPoller].map(_.get).flatMap { poller =>
          poller.select(pipe.sink, OP_READ).attempt.map {
            case Left(_: IllegalArgumentException) => true
            case _ => false
          }
        }
      }
    }

    "handles concurrent close" in {
      val (pool, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = 2)
      implicit val runtime: IORuntime = IORuntime.builder().setCompute(pool, shutdown).build()

      try {
        val test = IO
          .poller[SelectorPoller]
          .map(_.get)
          .flatMap { poller =>
            mkPipe.allocated.flatMap {
              case (pipe, close) =>
                poller.select(pipe.source, OP_READ).background.surround {
                  IO.sleep(1.millis) *> close
                }
            }
          }
          .replicateA_(1000)
          .as(true)
        test.unsafeRunSync() must beTrue
      } finally {
        runtime.shutdown()
      }
    }
  }

}
