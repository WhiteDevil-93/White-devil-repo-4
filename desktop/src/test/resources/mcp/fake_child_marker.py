"""A detached child that proves it's alive by continuously rewriting a marker file."""
import sys, time

marker_path = sys.argv[1]
while True:
    with open(marker_path, "w") as f:
        f.write(str(time.time()))
    time.sleep(0.1)
