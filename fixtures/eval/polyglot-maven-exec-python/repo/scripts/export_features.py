import json
import sys


def main(argv):
    schema = json.load(open(argv[1]))
    for field in schema["fields"]:
        print(field["name"])


if __name__ == "__main__":
    main(sys.argv[1:])
