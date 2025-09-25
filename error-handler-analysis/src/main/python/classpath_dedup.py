import argparse 

parser = argparse.ArgumentParser()
parser.add_argument('--cp', type=str)
args = parser.parse_args()

with open(args.cp, 'r') as f:
    cp = f.readline()
cps = set(cp.split(":"))

with open(args.cp, 'w') as f:
    f.write(":".join(cps))
