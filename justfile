MILL := './mill'

fetchMill:
	@[ -e {{MILL}} ] || curl -L https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.11/mill -o {{MILL}} && chmod +x {{MILL}}

run: scala verilator execute
# run class="CoreTest": fetchMill
# 	@{{MILL}} _.test.testOnly {{class}}

test unittest="_.test": fetchMill
	@{{MILL}} {{unittest}}

scala:
	@{{MILL}} rsd_rv32.run
	sed -i -E 's/(_dontOptimise[^\r\n\t\f\v \\]*)/\1\/* verilator public_flat_rd *\//' Machine.sv

verilator:
	verilator --cc Machine.sv --exe bench.cpp --build -j 0 -CFLAGS -O0 -CFLAGS -fuse-ld=mold --verilate-jobs 8 --trace-fst --trace-threads 8 --hierarchical

execute: 
	cd obj_dir && ./VMachine


[working-directory: 'doc']
update_src_list:
	(cd ../; find src -type f -name "*.scala") > srcs_files_list.txt

[working-directory: 'doc']
diagram_pdf:
	typst compile diagram_standalone.typ diagram.pdf

[working-directory: 'doc']
diagram:
	typst compile --format svg diagram_standalone.typ diagram.svg

[working-directory: 'doc']
spec: update_src_list diagram
	typst compile spec.typ --root ..

zig:
	zig build --release=small

disassemble:
	rasm2 -B -D -a riscv -b 32 -f zig-out/bin/rv32im_test.bin
