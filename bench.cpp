#include "VMachine.h"
#include "verilated.h"
#include "verilated_fst_c.h"

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VMachine* top = new VMachine;

    // Initialize VCD tracing
    VerilatedFstC* tfp = new VerilatedFstC;
    Verilated::traceEverOn(true);
    top->trace(tfp, 99);
    tfp->open("wave.fst");

    // Simulation loop
    for (int i = 0; i < 100; i++) {
        top->clock = !top->clock; // Toggle clock
        top->eval();
        tfp->dump(i);
    }

    // Finalize
    tfp->close();
    delete top;
    return 0;
}
