package ysyx

import chisel3._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule
import _root_.scpu.fpga.CdeConfigResolver

class ysyxSoCTop(implicit val parameters: Parameters) extends Module {

  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
  mdut.externalPins.get := DontCare

  if (YsyxPlatformParameters.isDpiSimulation) {
    val io = IO(new Bundle {
      val debug = Output(NpcSoCDebugBundle())
    })
    io.debug := mdut.debug.get
  } else {
    val io = IO(new Bundle { })
  }
}

object Elaborate extends App {
  val (entry, construction) = CdeConfigResolver.resolve("YsyxStandaloneConfig", Set("soc"))
  println(s"正在生成 ysyxSoC Verilog... Config=${entry.className}")
  implicit val parameters: Parameters = construction
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}

object ElaborateSim extends App {
  val (entry, construction) = CdeConfigResolver.resolve("YsyxSimulationConfig", Set("soc"))
  println(s"正在生成 ysyxSoC 仿真 Verilog... Config=${entry.className}")
  implicit val parameters: Parameters = construction
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}
