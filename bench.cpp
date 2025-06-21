#include "VMachine.h"
#include "VMachine___024root.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <ios>
#include <string>


#include <cctype>
#include <cstdio>
#include <cstddef>

void hexdump(const void* ptr, std::size_t buflen) {
    const unsigned char* buf = static_cast<const unsigned char*>(ptr);
    for (std::size_t i = 0; i < buflen; i += 16) {
        // Print offset
        std::printf("%06zx: ", i);

        // Print hex bytes
        for (std::size_t j = 0; j < 16; ++j) {
            if (i + j < buflen)
                std::printf("%02x ", buf[i + j]);
            else
                std::printf("   ");
        }

        // Separator
        std::printf(" ");

        // Print ASCII chars or dots
        for (std::size_t j = 0; j < 16 && i + j < buflen; ++j) {
            unsigned char c = buf[i + j];
            std::printf("%c", std::isprint(c) ? c : '.');
        }

        std::printf("\n");
    }
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VMachine* top = new VMachine;

    // Initialize VCD tracing
    VerilatedFstC* tfp = new VerilatedFstC;
    Verilated::traceEverOn(true);
    top->trace(tfp, 4);
    tfp->open("wave.fst");

    uint8_t* mem = (uint8_t *)top->rootp->Machine__DOT__core__DOT__mem__DOT__mem_ext__DOT__Memory.data();
    // std::ifstream test_prog_stream ("../zig-out/bin/rv32im_test.bin", std::ios_base::binary);

    // std::ifstream test_prog_stream ("../dhrystone_test/dhrystone.bin", std::ios_base::binary);
    std::ifstream test_prog_stream ("/home/b83c/chisel/ChiselRV32/dhrystone_test/test.bin", std::ios_base::binary);
    // std::ifstream test_prog_stream ("../dhrystone_test/dhry_.bin", std::ios_base::binary);
    // std::ifstream test_prog_stream ("../dhrystone/zig-out/bin/rv32im_test.bin", std::ios_base::binary);
    // FILE* test_prog_fd = fopen("./zig-out/bin/rv32im_test.bin", "r");
    test_prog_stream.seekg(0, std::ios::end);
    std::streampos fileSize = test_prog_stream.tellg();
    test_prog_stream.seekg(0, std::ios::beg);

    test_prog_stream.read(reinterpret_cast<char*>(mem), fileSize);
    test_prog_stream.close();

    printf("addr : %x\n", 0x80e0);
    hexdump(&mem[0x80e0], 100);

    const int end_cycle= 100000; 
    const int dump_start_cycle = 40000; 
    const int dump_end_cycle = 60000; 
    // const int dump_start_cycle = 40000; 
    // const int dump_end_cycle = 50000; 
    for (int i = 0; i < end_cycle; i++) {
        top->clock = !top->clock; // Toggle clock
        top->eval();
        if(i >= dump_start_cycle && i < dump_end_cycle) {
            tfp->dump(i-dump_start_cycle);
        }
        bool got = false;
        // top->rootp->mapping
        bool csr_write_valid = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__csr_wdata_dontOptimise_valid;
        int csr_addr = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__csr_addr_dontOptimise;
        int csr_data = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__csr_wdata_dontOptimise_bits;
        if(top->clock && csr_addr == 0xF && csr_write_valid) {
            printf("[%d] Received %d\n", i, csr_data);
        }

        bool write_to_mem = top->rootp->Machine__DOT__core__DOT__mem__DOT__is_write_mem_dontOptimise;
        int mem_addr = top->rootp->Machine__DOT__core__DOT__mem__DOT__mem_access_addr_dontOptimise;
        int mem_data = top->rootp->Machine__DOT__core__DOT__mem__DOT__data_into_mem_dontOptimise;
        if(top->clock && write_to_mem) {
            // printf("Writing to mem\n");
            if(mem_addr == 0x10001ff1) {
                printf("%c", (uint8_t)mem_data);
            }
            
            // printf("----- addr : 8385 -----\n");
            // hexdump(&mem[0x8385], 100);
            // printf("----- addr : 8385 -----\n");
        }
        if(top->clock && csr_addr == 0xA && csr_write_valid) {
            std::string str(reinterpret_cast<char*>(&mem[csr_data]));
            if(!str.compare("%T")) {
                printf("[%d] ", i);
            } else {
                // printf("addr : %x\n", csr_data);
                // hexdump(&mem[csr_data], 100);
                printf("%s", &mem[csr_data]);
                // printf("\n-------------\n%s\n-------------\n", &mem[csr_data]);
                // printf("\n-------------\n%s\n-------------\n", &mem[csr_data]);
            }
        }

        // top->rootp->mem_access
        // top->rootp->

        // int mem = top->rootp->mem
        // if(top->clock && )
        // if(top->clock) {
        //     top->rootp->Machine__DOT__core__DOT__rob__DOT__committing_entry_w_1_completed
            
        // }
    }
    printf("addr : %x\n", 0x80e0);
    hexdump(&mem[0x80f0], 100);

    // Finalize
    tfp->close();
    delete top;
    return 0;
}
