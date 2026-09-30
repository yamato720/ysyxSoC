package npc
import chisel3._
object Elaborate extends App {
  val (entry, construction) = ConfigResolver.resolve("StandaloneConfig")
  val config = construction.config
  val output = ElaborateOutput.directory("./generated")
  println(s"正在生成 Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}, pipeline=${config.pipeline.enablePipeline}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array(
      "--target-dir", output
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList(s"$output/CPU.sv")
  println("生成完成！")
}

// 使用外部内存的 Verilator 仿真 DPI-C 模式。
object ElaborateDPI extends App {
  val (entry, construction) = ConfigResolver.resolve("SimulationConfig")
  val config = construction.config
  val output = ElaborateOutput.directory("./generated-dpi")
  println(s"正在生成 NEMU 模式的 Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}, M 扩展后端=Chisel")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array(
      "--target-dir", output
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList(s"$output/CPU.sv")
  println("NEMU 模式 Verilog 生成完成！")
}

/** 仅供测试使用的 NEMU 生成入口，显式启用流水线。
  *
  * 正式的 Elaborate/ElaborateDPI 仍使用 PipelineConfig 默认值；保留此入口，
  * 使验证能构造可选配置而无需新增环境变量或 Make 开关。
  */
object ElaboratePipelineDPI extends App {
  val (entry, construction) = ConfigResolver.resolve("PipelineSimulationConfig")
  val config = construction.config
  println(s"正在生成流水线 NEMU Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array("--target-dir", "./generated-pipeline-dpi"),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated-pipeline-dpi/CPU.sv")
  println("流水线 NEMU Verilog 生成完成！")
}

/** 按 ASIC Config 生成完整 NPC RTL，供 Yosys 静态综合检查。 */
object ElaborateAsic extends App {
  require(args.contains("--target-dir"), "ElaborateAsic 需要 --target-dir")
  val (entry, construction) = CdeConfigResolver.resolve("SimulationConfig", Set("asic"))
  val config = construction(NpcCoreConfigKey)
  println(s"正在生成 NPC ASIC RTL... Config=${entry.className}, XLEN=${config.isa.xlen}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    args,
    Array(
      "--disable-annotation-unknown",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
  )
  val targetIndex = args.indexOf("--target-dir")
  ElaborateOutput.stripBlackBoxFileList(s"${args(targetIndex + 1)}/CPU.sv")
}
