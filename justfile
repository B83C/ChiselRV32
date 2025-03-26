MILL := './mill'

fetchMill: 
	@[ -e {{MILL}} ] || curl -L https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.9/mill -o {{MILL}} && chmod +x {{MILL}}

test unittest="ChiselRV32.test": fetchMill
	@{{MILL}} {{unittest}}

spec:
	typst compile spec.typ
