package ysyx

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import _root_.scpu.{ConstructionProfile, MakeConstructionConfig}
import _root_.scpu.fpga.{CdeConfigResolver, FpgaConfigParameters}

/** 为 L2 SoC 与 L3/L4 FPGA 终端 Config 生成规范化 profile。 */
object DescribeConfig extends App {
  require(args.length == 1, "用法：ysyx.DescribeConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("soc", "fpga-npc", "fpga-soc"))
  val metadata = construction match {
    case value: MakeConstructionConfig => value
    case _ => throw new IllegalArgumentException(s"${entry.className} 未声明 MakeConstructionConfig")
  }
  implicit val parameters: Parameters = construction
  val npcConfig = FpgaConfigParameters.npcCoreConfig
  val extra = entry.board.toSeq.flatMap { _ =>
    val platform = FpgaConfigParameters.platform
    val tools = FpgaConfigParameters.tools
    val build = FpgaConfigParameters.build
    Seq(
      "FPGA_BOARD" -> platform.board.name,
      "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
      "FPGA_TYPE" -> tools.fpgaType,
      "FPGA_PART" -> tools.part,
      "FPGA_PLATFORM" -> tools.platform,
      "FPGA_BOARD_PART" -> tools.boardPart,
      "FPGA_VIVADO_VERSION" -> tools.vivadoVersion,
      "FPGA_VITIS_VERSION" -> tools.vitisVersion,
      "FPGA_VITIS_TARGET" -> tools.vitisTarget,
      "FPGA_TIMING_WNS_MIN_NS" -> tools.timingWnsMinNs,
      "FPGA_VIVADO_SYNTH_JOBS" -> build.synthesisParallelJobs.toString,
      "FPGA_VIVADO_IMPL_JOBS" -> build.implementationParallelJobs.toString,
      "FPGA_VIVADO_IMPL_STRATEGY" -> tools.implementationStrategy,
      "FPGA_VIVADO_IMPL_STRATEGY_SEARCH" -> (if (build.implementationStrategySearch) "1" else "0"),
      "FPGA_MEMORY_KIND" -> tools.memoryKind,
      "FPGA_FLOATING_FALLBACK" -> tools.floatingFallback,
      "FPGA_MEMORY_HOST_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.memoryHostBase, 16)}",
      "FPGA_CONTROL_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.controlBase, 16)}",
      "FPGA_MAILBOX_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.mailboxBase, 16)}",
      "FPGA_DIV_IP_CYCLES" -> platform.dividerIpCycles.toString,
      "FPGA_DIV_ADAPTER_CYCLES" -> platform.dividerAdapterCycles.toString,
      "FPGA_PL_GIC_SPI" -> tools.plGicSpi.toString
    )
  }
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, metadata.capability, npcConfig, extra)
  )
}
