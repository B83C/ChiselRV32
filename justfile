MILL := './mill'

fetchMill: 
	@[ -e {{MILL}} ] || curl -L https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/0.12.9/mill -o {{MILL}} && chmod +x {{MILL}}

test unittest="_.test": fetchMill
	@{{MILL}} {{unittest}}

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
