package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import _root_.scpu.{ISAConfig, NpcCore, SimulationCoreComponents}
import _root_.scpu.fpga.FpgaCoreComponents
import _root_.scpu.protocol.{ArithmeticAssistPort, NpcCoreDebugBundle, NpcDispatchControlPort}
import ysyx.YsyxPlatformParameters

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = ChipLinkParam.idBits)
}

private object NpcSoCDebugBundle {
  def apply(): NpcCoreDebugBundle =
    new NpcCoreDebugBundle(ISAConfig(xlen = 32), masterAddrWidth = 32, masterDataWidth = 32)
}

class ysyx_00000000 extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val io_interrupt = Input(Bool())
    val io_master = AXI4Bundle(CPUAXI4BundleParameters())
    val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
  })
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
    AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "cpu",
        id   = IdRange(0, 1 << idBits))))).toSeq)
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private val npcConfig = YsyxPlatformParameters.npcCoreConfig
    val (master, _) = masterNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters())))
    val debug = if (YsyxPlatformParameters.enableNpcDebug) Some(IO(Output(NpcSoCDebugBundle()))) else None
    val putch = if (YsyxPlatformParameters.isFpga) Some(IO(Decoupled(UInt(8.W)))) else None
    val arithmeticAssist = if (YsyxPlatformParameters.isFpga && npcConfig.isa.F) {
      Some(IO(new ArithmeticAssistPort(32)))
    } else None
    val dispatchControl = if (YsyxPlatformParameters.isFpga) Some(IO(new NpcDispatchControlPort)) else None

    val cpu = Module(new ysyx_25120311)
    cpu.io.io_interrupt := interrupt
    cpu.io.io_slave <> slave
    master <> cpu.io.io_master
    debug.foreach(_ := cpu.io.debug.get)
    (putch zip cpu.io.putch).foreach { case (external, source) => external <> source }
    (arithmeticAssist zip cpu.io.arithmeticAssist).foreach { case (external, source) =>
      external.request.valid := source.request.valid
      external.request.bits := source.request.bits
      source.request.ready := external.request.ready
      source.response.valid := external.response.valid
      source.response.bits := external.response.bits
      external.response.ready := source.response.ready
      external.busy := source.busy
    }
    (dispatchControl zip cpu.io.dispatchControl).foreach { case (external, core) =>
      core.dispatchPermit := external.dispatchPermit
      external.dispatchFire := core.dispatchFire
    }
  }
}


class ysyx_25120311(implicit val parameters: Parameters) extends Module {
  private val npcConfig = YsyxPlatformParameters.npcCoreConfig
  val io = IO(new Bundle {
    val io_interrupt = Input(Bool())
    val io_master = AXI4Bundle(CPUAXI4BundleParameters())
    val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
    val debug = if (YsyxPlatformParameters.enableNpcDebug) Some(Output(NpcSoCDebugBundle())) else None
    val putch = if (YsyxPlatformParameters.isFpga) Some(Decoupled(UInt(8.W))) else None
    val arithmeticAssist = if (YsyxPlatformParameters.isFpga && npcConfig.isa.F) {
      Some(new ArithmeticAssistPort(32))
    } else None
    val dispatchControl = if (YsyxPlatformParameters.isFpga) Some(new NpcDispatchControlPort) else None
  })

  require(npcConfig.isa.xlen == 32, s"ysyxSoC requires XLEN=32, got ${npcConfig.isa.xlen}")
  val components = if (YsyxPlatformParameters.isFpga) FpgaCoreComponents else SimulationCoreComponents
  val cpu = Module(new NpcCore(npcConfig, components))

  cpu.io.interrupt := io.io_interrupt
  if (YsyxPlatformParameters.isFpga) {
    (io.putch zip cpu.io.putch).foreach { case (external, source) => external <> source }
    (io.arithmeticAssist zip cpu.io.arithmeticAssist).foreach { case (external, source) =>
      external.request.valid := source.request.valid
      external.request.bits := source.request.bits
      source.request.ready := external.request.ready
      source.response.valid := external.response.valid
      source.response.bits := external.response.bits
      external.response.ready := source.response.ready
      external.busy := source.busy
    }
    (io.dispatchControl zip cpu.io.dispatchControl).foreach { case (external, core) =>
      core.dispatchPermit := external.dispatchPermit
      external.dispatchFire := core.dispatchFire
    }
  } else {
    cpu.io.putch.foreach { putch =>
      if (YsyxPlatformParameters.isDpiSimulation) {
      val sink = Module(new SimPutchSink)
      sink.io.clock := clock
      sink.io.reset := reset
      sink.io.valid := putch.valid
      sink.io.bits := putch.bits
      putch.ready := sink.io.ready
      } else {
        putch.ready := true.B
      }
    }
  }

  // NPC 的自定义 AXI4-Full 端口与 Rocket AXI4Bundle 的公共标准字段逐一映射。
  io.io_master.aw.valid := cpu.io.master.aw.valid
  io.io_master.aw.bits.id := cpu.io.master.aw.bits.id
  io.io_master.aw.bits.addr := cpu.io.master.aw.bits.addr
  io.io_master.aw.bits.len := cpu.io.master.aw.bits.len
  io.io_master.aw.bits.size := cpu.io.master.aw.bits.size
  io.io_master.aw.bits.burst := cpu.io.master.aw.bits.burst
  io.io_master.aw.bits.lock := cpu.io.master.aw.bits.lock
  io.io_master.aw.bits.cache := cpu.io.master.aw.bits.cache
  io.io_master.aw.bits.prot := cpu.io.master.aw.bits.prot
  io.io_master.aw.bits.qos := cpu.io.master.aw.bits.qos
  cpu.io.master.aw.ready := io.io_master.aw.ready

  io.io_master.w.valid := cpu.io.master.w.valid
  io.io_master.w.bits.data := cpu.io.master.w.bits.data
  io.io_master.w.bits.strb := cpu.io.master.w.bits.strb
  io.io_master.w.bits.last := cpu.io.master.w.bits.last
  cpu.io.master.w.ready := io.io_master.w.ready

  cpu.io.master.b.valid := io.io_master.b.valid
  cpu.io.master.b.bits.id := io.io_master.b.bits.id
  cpu.io.master.b.bits.resp := io.io_master.b.bits.resp
  io.io_master.b.ready := cpu.io.master.b.ready

  io.io_master.ar.valid := cpu.io.master.ar.valid
  io.io_master.ar.bits.id := cpu.io.master.ar.bits.id
  io.io_master.ar.bits.addr := cpu.io.master.ar.bits.addr
  io.io_master.ar.bits.len := cpu.io.master.ar.bits.len
  io.io_master.ar.bits.size := cpu.io.master.ar.bits.size
  io.io_master.ar.bits.burst := cpu.io.master.ar.bits.burst
  io.io_master.ar.bits.lock := cpu.io.master.ar.bits.lock
  io.io_master.ar.bits.cache := cpu.io.master.ar.bits.cache
  io.io_master.ar.bits.prot := cpu.io.master.ar.bits.prot
  io.io_master.ar.bits.qos := cpu.io.master.ar.bits.qos
  cpu.io.master.ar.ready := io.io_master.ar.ready

  cpu.io.master.r.valid := io.io_master.r.valid
  cpu.io.master.r.bits.id := io.io_master.r.bits.id
  cpu.io.master.r.bits.data := io.io_master.r.bits.data
  cpu.io.master.r.bits.resp := io.io_master.r.bits.resp
  cpu.io.master.r.bits.last := io.io_master.r.bits.last
  io.io_master.r.ready := cpu.io.master.r.ready

  // 当前没有 ChipLink/DMA 主设备访问 CPU，先以永久反压的 AXI slave 端口占位。
  io.io_slave.aw.ready := false.B
  io.io_slave.w.ready := false.B
  io.io_slave.b.valid := false.B
  io.io_slave.b.bits.id := 0.U
  io.io_slave.b.bits.resp := 0.U
  io.io_slave.ar.ready := false.B
  io.io_slave.r.valid := false.B
  io.io_slave.r.bits.id := 0.U
  io.io_slave.r.bits.data := 0.U
  io.io_slave.r.bits.resp := 0.U
  io.io_slave.r.bits.last := false.B

  io.debug.foreach(_ := cpu.io.debug.get)
}
