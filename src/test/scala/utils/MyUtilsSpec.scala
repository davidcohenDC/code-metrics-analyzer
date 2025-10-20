package utils

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import utils.MyUtils

class MyUtilsSpec extends AnyWordSpec with Matchers:

  "Parallelism.getParallelism" should {
    "return configured processors when positive" in {
      val config = ConfigFactory.parseString("source.processors = 4")
      MyUtils.Parallelism.getParallelism(config) shouldBe 4
    }

    "fallback to cpu-based default when config missing or invalid" in {
      val fallback = Runtime.getRuntime.availableProcessors() * 2

      val missing = ConfigFactory.empty()
      MyUtils.Parallelism.getParallelism(missing) shouldBe fallback

      val invalid = ConfigFactory.parseString("source.processors = not-a-number")
      MyUtils.Parallelism.getParallelism(invalid) shouldBe fallback
    }

    "double available processors when configured value is non-positive" in {
      val config = ConfigFactory.parseString("source.processors = 0")
      MyUtils.Parallelism.getParallelism(config) shouldBe Runtime.getRuntime.availableProcessors() * 2
    }
  }
