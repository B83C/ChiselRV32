#include "VMachine.h"
#include "VMachine___024root.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <ios>

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VMachine* top = new VMachine;

    // Initialize VCD tracing
    VerilatedFstC* tfp = new VerilatedFstC;
    Verilated::traceEverOn(true);
    top->trace(tfp, 99);
    tfp->open("wave.fst");

    uint8_t* mem = (uint8_t *)top->rootp->Machine__DOT__core__DOT__mem__DOT__mem_ext__DOT__Memory.data();
    std::ifstream test_prog_stream ("../zig-out/bin/rv32im_test.bin", std::ios_base::binary);
    // FILE* test_prog_fd = fopen("./zig-out/bin/rv32im_test.bin", "r");
    test_prog_stream.seekg(0, std::ios::end);
    std::streampos fileSize = test_prog_stream.tellg();
    test_prog_stream.seekg(0, std::ios::beg);

    test_prog_stream.read(reinterpret_cast<char*>(mem), fileSize);
    test_prog_stream.close();

    for (int i = 0; i < 3000; i++) {
        top->clock = !top->clock; // Toggle clock
        top->eval();
        tfp->dump(i);
        bool got = false;
        // top->rootp->mapping
        bool csr_write_valid = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__all_devices_0__DOT__io_wdata_valid_0;
        int csr_addr = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__all_devices_0__DOT__io_addr_0;
        int csr_data = top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__all_devices_0__DOT__io_wdata_bits_0;
        if(top->clock && csr_addr == 0xF && csr_write_valid) {
            printf("[%d] Received %d\n", i, csr_data);
            
        }
        if(top->clock && csr_addr == 0xA && csr_write_valid) {
            printf("[%d] %s", i, &mem[csr_data]);
        }
        // if(top->clock && top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__addr == 0xF && top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__wdata_valid == true) {
        //     printf("Received %d\n", top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__wdata_bits);
            
        // }
        // if(top->clock && top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__addr == 0xA && top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__wdata_valid == true) {
        //     printf("%s", &mem[top->rootp->Machine__DOT__core__DOT__exu__DOT__csru__DOT__wdata_bits]);
        // }
    }

    // Finalize
    tfp->close();
    delete top;
    return 0;
}
