MEMORY {
  PROG : ORIGIN = 0x0, LENGTH = 512K
  /* DATA : ORIGIN = 128K, LENGTH = 64K */
  /* STACK : ORIGIN = 196K, LENGTH = 64K */
  /* BSS : ORIGIN = 256K, LENGTH = 128K */
}

__stack_size = 32K;

ENTRY(_start)

SECTIONS {
   .text : ALIGN(16) {
     *(.text)
   } > PROG

   .data : ALIGN(16) {
     *(.rodata)
     *(.rodata.*)
     *(.data)
     *(.data.*)
   } > PROG 

   .bss : ALIGN(16) {
     *(.bss)
     *(.bss.*)
   } > PROG

   .stack : ALIGN(16) {
     _estack = .;
   } > PROG

   _sstack = _estack + __stack_size;
 }
