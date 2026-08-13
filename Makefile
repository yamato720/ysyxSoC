V_FILE_GEN   = build/ysyxSoCTop.sv
V_FILE_FINAL = build/ysyxSoCFull.v
SIM_BUILD_DIR ?= build-sim
SIM_V_FILE_GEN   = $(SIM_BUILD_DIR)/ysyxSoCTop.sv
SIM_V_FILE_FINAL = $(SIM_BUILD_DIR)/ysyxSoCFull.v
ifneq ($(INTERNAL_CONSTRUCTION),1)
$(error ysyxSoC Make 生成入口已内部化；请使用 make -C npc build config=<SoC或FPGA-SoC Config>)
endif
NPC_XLEN ?= 32
NPC_TARGET ?= SOC
NPC_PIPELINE ?= 1
NPC_INTERLOCK ?= 1
NPC_ID_FWD ?= $(NPC_PIPELINE)
NPC_EX_FWD ?= $(NPC_PIPELINE)
NPC_F ?= 0
NPC_ARITH_BACKEND ?= model
NPC_ARITH_OUTPUT_FIFO ?= 4
NPC_MUL_CYCLES ?= 3
NPC_MUL_II ?= 1
NPC_DIV_CYCLES ?= 37
NPC_DIV_II ?= 1
NPC_FADD_CYCLES ?= 3
NPC_FADD_II ?= 1
NPC_FMUL_CYCLES ?= 4
NPC_FMUL_II ?= 1
NPC_FDIV_CYCLES ?= 29
NPC_FDIV_II ?= 1
NPC_FFMA_CYCLES ?= 4
NPC_FFMA_II ?= 1
NPC_FSQRT_CYCLES ?= 29
NPC_FSQRT_II ?= 1
NPC_FCVT_CYCLES ?= 7
NPC_FCVT_II ?= 1
NPC_FCMP_CYCLES ?= 3
NPC_FCMP_II ?= 1
NPC_MEMORY_BASE ?= 0x80000000
NPC_MEMORY_SIZE ?= 0x08000000
NPC_FPGA_BOARD ?= zcu102
NPC_FPGA_CLOCK_MHZ ?= 0
NPC_FPGA_MEMORY_HOST_BASE ?= 0x0
NPC_FPGA_CONTROL_BASE ?= 0xa0000000
NPC_FPGA_MAILBOX_BASE ?= 0xa0010000
NPC_FPGA_DIV_IP_CYCLES ?= 0
NPC_FPGA_DIV_ADAPTER_CYCLES ?= 0
FPGA_OUTPUT ?= build-fpga

include ../../config-catalog.mk

ifneq ($(strip $(config)),)
  SOC_CONFIG_RESOLVED := $(call scpu_config_resolve,$(config),soc)
  ifneq ($(call scpu_config_error,$(SOC_CONFIG_RESOLVED)),)
    $(error $(patsubst !%,%,$(call scpu_config_error,$(SOC_CONFIG_RESOLVED))))
  endif
  SOC_SELECTED_SCALA_CONFIG := $(call scpu_config_field,1,$(SOC_CONFIG_RESOLVED))
  SOC_CONFIG_TARGET := $(call scpu_config_field,4,$(SOC_CONFIG_RESOLVED))
  ifneq ($(SOC_CONFIG_TARGET),SOC)
    $(error config=$(SOC_SELECTED_SCALA_CONFIG) requires NPC_TARGET=SOC)
  endif
endif

SOC_VERILOG_SCALA_CONFIG := $(if $(SOC_SELECTED_SCALA_CONFIG),$(SOC_SELECTED_SCALA_CONFIG),ysyx.YsyxElaborateConfig)
SOC_SIM_SCALA_CONFIG := $(if $(SOC_SELECTED_SCALA_CONFIG),$(SOC_SELECTED_SCALA_CONFIG),ysyx.YsyxSimulationConfig)
SOC_MILL_CONFIG_PROP = -Dnpc.config=$(1)

ifneq ($(filter 1 true yes on,$(NPC_F)),)
ifeq ($(NPC_ARITH_BACKEND),ip)
$(error NPC_ARITH_BACKEND=ip cannot be used with NPC_F=1: Vivado 2022.2 FPO lacks dynamic RISC-V rounding/RMM, NX reporting, and unsigned float-to-integer conversion; use NPC_ARITH_BACKEND=model)
endif
endif

SOC_CONFIG = verilog_config=$(SOC_VERILOG_SCALA_CONFIG),sim_config=$(SOC_SIM_SCALA_CONFIG)
V_CONFIG_STAMP = $(V_FILE_FINAL).config
SIM_V_CONFIG_STAMP = $(SIM_V_FILE_FINAL).config
SCALA_FILES = $(shell find src/ ../fpga-harness/src/ysyxSoC -name "*.scala")
CONFIG_SCALA_FILES = $(shell find ../configs -name "*.scala")
CONFIG_RESOURCE_FILES = $(shell find ../configs/resources -type f)
# ysyxSoC 的 CPU wrapper 直接引用同级目录中的 NPC 核心与 FPGA 公共源码。
# 将它们纳入生成依赖，避免 AXI 或核心改动后继续误用旧 Verilog。
NPC_SCALA_FILES = $(shell find ../rv-core/scala ../accelerators/common/scala ../accelerators/spmv/scala -name "*.scala")
NPC_RESOURCE_FILES = $(shell find ../ip-interface/resources -type f)

# Firtool 版本
FIRTOOL_VERSION = 1.105.0
FIRTOOL_PATCH_DIR = $(shell pwd)/patch/firtool

.PHONY: FORCE
FORCE:

$(V_CONFIG_STAMP): FORCE
	@mkdir -p $(@D)
	@if [ ! -f $@ ] || [ "$$(cat $@)" != "$(SOC_CONFIG)" ]; then \
		printf '%s\n' '$(SOC_CONFIG)' > $@; \
		rm -f $(V_FILE_FINAL); \
	fi

$(SIM_V_CONFIG_STAMP): FORCE
	@mkdir -p $(@D)
	@if [ ! -f $@ ] || [ "$$(cat $@)" != "$(SOC_CONFIG)" ]; then \
		printf '%s\n' '$(SOC_CONFIG)' > $@; \
		rm -f $(SIM_V_FILE_FINAL); \
	fi

$(V_FILE_FINAL): $(V_CONFIG_STAMP) $(SCALA_FILES) $(CONFIG_SCALA_FILES) $(CONFIG_RESOURCE_FILES) $(NPC_SCALA_FILES) $(NPC_RESOURCE_FILES)
# Replace firtool with a newer version
# TODO: This can be removed after chisel publishes a new version
	@./patch/update-firtool.sh $(FIRTOOL_VERSION) $(FIRTOOL_PATCH_DIR)
	NPC_SCALA_CONFIG=$(SOC_VERILOG_SCALA_CONFIG) CHISEL_FIRTOOL_PATH=$(FIRTOOL_PATCH_DIR)/firtool-$(FIRTOOL_VERSION)/bin \
	mill -i $(call SOC_MILL_CONFIG_PROP,$(SOC_VERILOG_SCALA_CONFIG)) ysyxsoc.runMain ysyx.Elaborate --target-dir $(@D)
	mv $(V_FILE_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

verilog: $(V_FILE_FINAL)

$(SIM_V_FILE_FINAL): $(SIM_V_CONFIG_STAMP) $(SCALA_FILES) $(CONFIG_SCALA_FILES) $(CONFIG_RESOURCE_FILES) $(NPC_SCALA_FILES) $(NPC_RESOURCE_FILES)
	@./patch/update-firtool.sh $(FIRTOOL_VERSION) $(FIRTOOL_PATCH_DIR)
	NPC_SCALA_CONFIG=$(SOC_SIM_SCALA_CONFIG) CHISEL_FIRTOOL_PATH=$(FIRTOOL_PATCH_DIR)/firtool-$(FIRTOOL_VERSION)/bin \
	mill -i $(call SOC_MILL_CONFIG_PROP,$(SOC_SIM_SCALA_CONFIG)) ysyxsoc.runMain ysyx.ElaborateSim --target-dir $(@D)
	mv $(SIM_V_FILE_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

sim-verilog:
	@$(MAKE) $(SIM_V_FILE_FINAL)

fpga-verilog:
	@./patch/update-firtool.sh $(FIRTOOL_VERSION) $(FIRTOOL_PATCH_DIR)
	@mkdir -p "$(FPGA_OUTPUT)"
	NPC_SCALA_CONFIG=$(FPGA_SCALA_CONFIG) CHISEL_FIRTOOL_PATH=$(FIRTOOL_PATCH_DIR)/firtool-$(FIRTOOL_VERSION)/bin \
		mill -i ysyxsoc.runMain ysyx.ElaborateFPGA --target-dir "$(FPGA_OUTPUT)"
	@test -f "$(FPGA_OUTPUT)/NpcFpgaTop.sv"
	@test -f "$(FPGA_OUTPUT)/fpga-parameters.env"
	@sed -i '/firrtl_black_box_resource_files.f/, $$d' "$(FPGA_OUTPUT)/NpcFpgaTop.sv"

clean:
	-rm -rf build/ build-sim/

dev-init:
	git submodule update --init --recursive
	cd rocket-chip && git apply ../patch/rocket-chip.patch

.PHONY: verilog sim-verilog fpga-verilog clean dev-init
