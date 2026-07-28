package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.system.SimAXIMem
import _root_.npc.ISAConfig
import _root_.npc.protocol.NpcDispatchControlPort
import ysyx.YsyxPlatformParameters

object AXI4SlaveNodeGenerator {
  def apply(params: Option[MasterPortParams], address: Seq[AddressSet])(implicit valName: ValName) =
    AXI4SlaveNode(params.map(p => AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = address,
          executable    = p.executable,
          supportsWrite = TransferSizes(1, p.maxXferBytes),
          supportsRead  = TransferSizes(1, p.maxXferBytes))),
        beatBytes = p.beatBytes
      )).toSeq)
}

class ysyxSoCASIC(implicit p: Parameters) extends LazyModule {
  private val npcConfig = YsyxPlatformParameters.npcCoreConfig
  private val useDpiSimBackend = YsyxPlatformParameters.isDpiSimulation
  private val useFpgaBackend = YsyxPlatformParameters.isFpga
  val xbar = AXI4Xbar()
  val xbar2 = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU(idBits = ChipLinkParam.idBits))
  val chipMaster = if (YsyxPlatformParameters.hasChipLink) Some(LazyModule(new ChipLinkMaster)) else None
  val chiplinkNode = if (YsyxPlatformParameters.hasChipLink) Some(AXI4SlaveNodeGenerator(p(ExtBus), ChipLinkParam.allSpace)) else None

  val luart = if (useFpgaBackend) None else Some(LazyModule(new APBUart16550(AddressSet.misaligned(0x10000000, 0x1000))))
  val lgpio = if (useFpgaBackend) None else Some(LazyModule(new APBGPIO(AddressSet.misaligned(0x10002000, 0x10))))
  val lkeyboard = if (useFpgaBackend) None else Some(LazyModule(new APBKeyboard(AddressSet.misaligned(0x10011000, 0x8))))
  val lvga = if (useFpgaBackend) None else Some(LazyModule(new APBVGA(AddressSet.misaligned(0x21000000, 0x200000))))
  val lspi = if (useFpgaBackend) None else Some(LazyModule(new APBSPI(
    AddressSet.misaligned(0x10001000, 0x1000) ++    // SPI controller
    AddressSet.misaligned(0x30000000, 0x10000000)   // XIP flash
  )))
  val lpsram = if (useDpiSimBackend || useFpgaBackend) None else
    Some(LazyModule(new APBPSRAM(AddressSet.misaligned(0x80000000L, 0x400000))))
  // Match NEMU's 128 MiB physical-memory window in simulation mode.
  val lsimPmem = if (useDpiSimBackend) Some(LazyModule(new APBDpiRam(AddressSet.misaligned(0x80000000L, 0x08000000)))) else None
  // NEMU's device ABI occupies this window. It deliberately replaces SDRAM
  // only in simulation, because their address maps overlap.
  val lsimMmio = if (useDpiSimBackend) Some(LazyModule(new APBDpiMmio(AddressSet.misaligned(0xa0000000L, 0x02000000)))) else None
  val lmrom = if (useFpgaBackend) None else
    Some(LazyModule(new AXI4MROM(AddressSet.misaligned(0x20000000, 0x1000))))
  val sramNode = AXI4RAM(AddressSet.misaligned(0x0f000000, 0x2000).head, false, true, 4, None, Nil, false)
  val fpgaMemoryNode = if (useFpgaBackend) Some(AXI4SlaveNodeGenerator(
    p(ExtBus), AddressSet.misaligned(npcConfig.memory.mainMemoryBase, npcConfig.memory.mainMemorySize)
  )) else None

  val sdramAddressSet = AddressSet.misaligned(0xa0000000L, 0x2000000)
  val lsdram_apb = if (!useDpiSimBackend && !useFpgaBackend && !YsyxPlatformParameters.useAxiSdram) Some(LazyModule(new APBSDRAM(sdramAddressSet))) else None
  val lsdram_axi = if (!useDpiSimBackend && !useFpgaBackend && YsyxPlatformParameters.useAxiSdram) Some(LazyModule(new AXI4SDRAM(sdramAddressSet))) else None

  (lspi.map(_.node) ++ luart.map(_.node) ++ lgpio.map(_.node) ++
    lkeyboard.map(_.node) ++ lvga.map(_.node) ++ lpsram.map(_.node) ++
    lsimPmem.map(_.node) ++ lsimMmio.map(_.node)).map(_ := apbxbar)
  if (!useFpgaBackend) apbxbar := APBDelayer() := AXI4ToAPB() := AXI4Buffer() := xbar2
  (lmrom.map(_.node).toSeq :+ sramNode).map(_ := xbar2)
  xbar2 := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
  if (!useDpiSimBackend && !useFpgaBackend) {
    if (YsyxPlatformParameters.useAxiSdram) lsdram_axi.get.node := ysyx.AXI4Delayer() := xbar
    else                    lsdram_apb.get.node := apbxbar
  }
  fpgaMemoryNode.foreach { node => node := AXI4Buffer() := xbar }
  if (YsyxPlatformParameters.hasChipLink) chiplinkNode.get := xbar
  xbar := cpu.masterNode
  val fpgaMemory = InModuleBody {
    if (useFpgaBackend) fpgaMemoryNode.get.makeIOs() else Seq.empty
  }

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    // generate delayed reset for cpu, since chiplink should finish reset
    // to initialize some async modules before accept any requests from cpu
    cpu.module.reset := SynchronizerShiftReg(reset.asBool, 10) || reset.asBool

    val fpga_io = if (YsyxPlatformParameters.hasChipLink) Some(IO(chiselTypeOf(chipMaster.get.module.fpga_io))) else None
    if (YsyxPlatformParameters.hasChipLink) {
      // connect chiplink slave interface to crossbar
      (chipMaster.get.slave zip chiplinkNode.get.in) foreach { case (io, (bundle, _)) => io <> bundle }

      // connect chiplink dma interface to cpu
      cpu.module.slave <> chipMaster.get.master_mem(0)

      // expose chiplink fpga I/O interface as ports
      fpga_io.get <> chipMaster.get.module.fpga_io
    } else {
      cpu.module.slave := DontCare
    }

    // connect interrupt signal to cpu
    val intr_from_chipSlave = IO(Input(Bool()))
    cpu.module.interrupt := intr_from_chipSlave
    val debug = if (YsyxPlatformParameters.enableNpcDebug) Some(IO(Output(NpcSoCDebugBundle()))) else None
    val putch = if (useFpgaBackend) Some(IO(Decoupled(UInt(8.W)))) else None
    val dispatchControl = if (useFpgaBackend) Some(IO(new NpcDispatchControlPort)) else None
    debug.foreach(_ := cpu.module.debug.get)
    (putch zip cpu.module.putch).foreach { case (external, source) => external <> source }
    (dispatchControl zip cpu.module.dispatchControl).foreach { case (external, core) =>
      core.dispatchPermit := external.dispatchPermit
      external.dispatchFire := core.dispatchFire
    }

    val sdramBundle = if (YsyxPlatformParameters.useAxiSdram) lsdram_axi.map(_.module.sdram_bundle)
                      else                    lsdram_apb.map(_.module.sdram_bundle)

    // expose slave I/O interface as ports
    val spi = lspi.map(device => IO(chiselTypeOf(device.module.spi_bundle)))
    val uart = luart.map(device => IO(chiselTypeOf(device.module.uart)))
    val psram = lpsram.map(device => IO(chiselTypeOf(device.module.qspi_bundle)))
    val sdram = sdramBundle.map(bundle => IO(chiselTypeOf(bundle)))
    val gpio = lgpio.map(device => IO(chiselTypeOf(device.module.gpio_bundle)))
    val ps2 = lkeyboard.map(device => IO(chiselTypeOf(device.module.ps2_bundle)))
    val vga = lvga.map(device => IO(chiselTypeOf(device.module.vga_bundle)))
    (uart zip luart).foreach { case (io, device) => io <> device.module.uart }
    (spi zip lspi).foreach { case (io, device) => io <> device.module.spi_bundle }
    (psram zip lpsram).foreach { case (io, device) => io <> device.module.qspi_bundle }
    (sdram zip sdramBundle).foreach { case (io, bundle) => io <> bundle }
    (gpio zip lgpio).foreach { case (io, device) => io <> device.module.gpio_bundle }
    (ps2 zip lkeyboard).foreach { case (io, device) => io <> device.module.ps2_bundle }
    (vga zip lvga).foreach { case (io, device) => io <> device.module.vga_bundle }
  }
}

class ysyxSoCFPGA(implicit p: Parameters) extends ChipLinkSlave


class ysyxSoCFull(implicit p: Parameters) extends LazyModule {
  val asic = LazyModule(new ysyxSoCASIC)
  ElaborationArtefacts.add("graphml", graphML)

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    val masic = asic.module
    val debug = if (YsyxPlatformParameters.enableNpcDebug) Some(IO(Output(NpcSoCDebugBundle()))) else None
    (debug zip masic.debug).foreach { case (trace, source) => trace := source }

    if (YsyxPlatformParameters.hasChipLink) {
      val fpga = LazyModule(new ysyxSoCFPGA)
      val mfpga = Module(fpga.module)
      masic.dontTouchPorts()

      masic.fpga_io.get.b2c <> mfpga.fpga_io.c2b
      mfpga.fpga_io.b2c <> masic.fpga_io.get.c2b

      (fpga.master_mem zip fpga.axi4MasterMemNode.in).map { case (io, (_, edge)) =>
        val mem = LazyModule(new SimAXIMem(edge,
          base = ChipLinkParam.mem.base, size = ChipLinkParam.mem.mask + 1))
        Module(mem.module)
        mem.io_axi4.head <> io
      }

      fpga.master_mmio.map(_ := DontCare)
      fpga.slave.map(_ := DontCare)
    }

    masic.intr_from_chipSlave := false.B

    masic.spi.foreach { spiPort =>
      val flash = Module(new flash)
      flash.io <> spiPort
      flash.io.ss := spiPort.ss(0)
      val bitrev = Module(new bitrev)
      bitrev.io <> spiPort
      bitrev.io.ss := spiPort.ss(7)
      spiPort.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_&&_)
    }

    masic.psram.foreach { psramPort =>
      val psram = Module(new psram)
      psram.io <> psramPort
    }
    masic.sdram.foreach { sdramPort =>
      val sdram = Module(new sdram)
      sdram.io <> sdramPort
    }

    val externalPins = if (YsyxPlatformParameters.isFpga) None else Some(IO(new Bundle{
      val gpio = chiselTypeOf(masic.gpio.get)
      val ps2 = chiselTypeOf(masic.ps2.get)
      val vga = chiselTypeOf(masic.vga.get)
      val uart = chiselTypeOf(masic.uart.get)
    }))
    externalPins.foreach { pins =>
      pins.gpio <> masic.gpio.get
      pins.ps2 <> masic.ps2.get
      pins.vga <> masic.vga.get
      pins.uart <> masic.uart.get
    }
  }
}
