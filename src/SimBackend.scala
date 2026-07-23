package ysyx

import chisel3._
import chisel3.util.HasBlackBoxInline

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

/** Consume the core's FPGA-style serial event through NEMU's exact MMIO ABI. */
class SimPutchSink extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val valid = Input(Bool())
    val bits = Input(UInt(8.W))
    val ready = Output(Bool())
  })

  setInline(
    "SimPutchSink.v",
    """module SimPutchSink(
      |  input        clock,
      |  input        reset,
      |  input        valid,
      |  input  [7:0] bits,
      |  output       ready
      |);
      |  import "DPI-C" function void mmio_write_word(
      |    input int addr, input int len, input int word_bytes,
      |    input longint unsigned word_data, input byte unsigned strb
      |  );
      |
      |  assign ready = 1'b1;
      |  always @(posedge clock) begin
      |    if (!reset && valid)
      |      mmio_write_word(32'ha00003f8, 1, 4, {56'b0, bits}, 8'h01);
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Verilator-only APB memory backed by NEMU physical memory through the XLEN DPI ABI. */
class SimAPBDpiRam extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  })

  setInline(
    "SimAPBDpiRam.v",
    """module SimAPBDpiRam(
      |  input         clock,
      |  input         reset,
      |  input  [31:0] in_paddr,
      |  input         in_psel,
      |  input         in_penable,
      |  input  [2:0]  in_pprot,
      |  input         in_pwrite,
      |  input  [31:0] in_pwdata,
      |  input  [3:0]  in_pstrb,
      |  output        in_pready,
      |  output [31:0] in_prdata,
      |  output        in_pslverr
      |);
      |  import "DPI-C" function void pmem_read_word(
      |    input int addr, input int word_bytes, output longint unsigned data
      |  );
      |  import "DPI-C" function void pmem_write_word(
      |    input int addr, input int word_bytes, input longint unsigned data, input byte unsigned strb
      |  );
      |
      |  reg [63:0] read_data;
      |
      |  // The APB setup phase starts the DPI access. The registered read result
      |  // is returned during the following APB access phase.
      |  always @(posedge clock) begin
      |    if (reset) begin
      |      read_data <= 64'b0;
      |    end else if (in_psel && !in_penable) begin
      |      if (in_pwrite)
      |        pmem_write_word(in_paddr, 4, {32'b0, in_pwdata}, in_pstrb);
      |      else
      |        pmem_read_word(in_paddr, 4, read_data);
      |    end
      |  end
      |
      |  assign in_pready = in_psel && in_penable;
      |  assign in_pslverr = 1'b0;
      |  assign in_prdata = read_data[31:0];
      |endmodule
      |""".stripMargin
  )
}

class APBDpiRam(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val memory = Module(new SimAPBDpiRam)

    memory.io.clock := clock
    memory.io.reset := reset
    memory.io.in <> in
  }
}

/** Verilator-only APB bridge to NEMU's memory-mapped devices.
  *
  * NEMU devices use the 0xa0000000..0xa1ffffff ABI, which differs from the
  * native ysyxSoC peripheral map. This bridge keeps NEMU as the device model
  * for SoC simulation without changing the hardware address map.
  */
class SimAPBDpiMmio extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  })

  setInline(
    "SimAPBDpiMmio.v",
    """module SimAPBDpiMmio(
      |  input         clock,
      |  input         reset,
      |  input  [31:0] in_paddr,
      |  input         in_psel,
      |  input         in_penable,
      |  input  [2:0]  in_pprot,
      |  input         in_pwrite,
      |  input  [31:0] in_pwdata,
      |  input  [3:0]  in_pstrb,
      |  output        in_pready,
      |  output [31:0] in_prdata,
      |  output        in_pslverr
      |);
      |  import "DPI-C" function void mmio_read_word(
      |    input int addr, input int len, input int word_bytes, output longint unsigned word_data
      |  );
      |  import "DPI-C" function void mmio_write_word(
      |    input int addr, input int len, input int word_bytes,
      |    input longint unsigned word_data, input byte unsigned strb
      |  );
      |
      |  reg [63:0] read_data;
      |
      |  function automatic integer access_len(input [3:0] strb);
      |    begin
      |      case (strb)
      |        4'b0001, 4'b0010, 4'b0100, 4'b1000: access_len = 1;
      |        4'b0011, 4'b1100:                   access_len = 2;
      |        default:                              access_len = 4;
      |      endcase
      |    end
      |  endfunction
      |
      |  // Start DPI work in APB setup; return the registered result in access.
      |  always @(posedge clock) begin
      |    if (reset) begin
      |      read_data <= 64'b0;
      |    end else if (in_psel && !in_penable) begin
      |      if (in_pwrite)
      |        mmio_write_word(in_paddr, access_len(in_pstrb), 4, {32'b0, in_pwdata}, in_pstrb);
      |      else
      |        mmio_read_word(in_paddr, access_len(in_pstrb), 4, read_data);
      |    end
      |  end
      |
      |  assign in_pready = in_psel && in_penable;
      |  assign in_pslverr = 1'b0;
      |  assign in_prdata = read_data[31:0];
      |endmodule
      |""".stripMargin
  )
}

/** Verilator-only conversion from a core memory fault into NEMU_ABORT. */
class SimMemoryFaultSink extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val valid = Input(Bool())
    val addr = Input(UInt(32.W))
    val write = Input(Bool())
    val len = Input(UInt(4.W))
    val reason = Input(UInt(3.W))
  })

  setInline(
    "SimMemoryFaultSink.v",
    """module SimMemoryFaultSink(
      |  input clock, input reset, input valid, input [31:0] addr,
      |  input write, input [3:0] len, input [2:0] reason
      |);
      |  reg reported;
      |  import "DPI-C" function void memory_fault(
      |    input int addr, input bit write, input int len, input int reason
      |  );
      |  always @(posedge clock) begin
      |    if (reset) reported <= 1'b0;
      |    else if (valid && !reported) begin
      |      memory_fault(addr, write, len, reason);
      |      reported <= 1'b1;
      |    end
      |  end
      |endmodule
      |""".stripMargin
  )
}

class APBDpiMmio(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = false,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val mmio = Module(new SimAPBDpiMmio)

    mmio.io.clock := clock
    mmio.io.reset := reset
    mmio.io.in <> in
  }
}
