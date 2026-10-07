"""Records every line the client writes to its stdin into the file named by argv[1], and never replies."""
import sys

out = open(sys.argv[1], "w", encoding="utf-8")
for line in sys.stdin:
    out.write(line if line.endswith("\n") else line + "\n")
    out.flush()
