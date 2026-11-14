#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Find the 中国天气网 city id for a place, so the photo screensaver can show domestic weather.

Usage:
    python tools/weather_city.py 北京
    python tools/weather_city.py 深圳
    python tools/weather_city.py 杭州 --write weather.txt

The lookup is offline: tools/city_codes.json holds the city table published by 中国天气网
itself, so nothing is fetched and the answer is the same every time. The id goes into the
screensaver's files dir as weather.txt (one line: "<cityid>", or "<cityid> <key>").
No account and no key are needed for the current-conditions endpoint.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CODES = os.path.join(HERE, "city_codes.json")
# trailing words that people type but the table does not keep
SUFFIXES = ("特别行政区", "自治区", "自治州", "地区", "盟", "市", "区", "县", "州")


def load():
    with open(CODES, encoding="utf-8") as handle:
        return json.load(handle)


def strip_suffix(name):
    for suffix in SUFFIXES:
        if len(name) > len(suffix) and name.endswith(suffix):
            return name[: -len(suffix)]
    return name


def search(table, query):
    """Candidates for a place name, best first: the city itself, then a same-named district,
    then other cities, then partial matches."""
    query = query.strip()
    bare = strip_suffix(query)
    tiers = {0: [], 1: [], 2: [], 3: []}
    for areaid, (province, city, name) in table.items():
        if name == query or name == bare:
            # the city's own entry beats a district that happens to share the name
            tier = 0 if name == city else 1
        elif city == query or city == bare:
            tier = 2
        elif query and (query in name or bare in name) and len(bare) >= 2:
            tier = 3
        else:
            continue
        tiers[tier].append((areaid, province, city, name))
    return tiers[0] + tiers[1] + tiers[2] + tiers[3]


def main():
    parser = argparse.ArgumentParser(description="Find the 中国天气网 city id for a place.")
    parser.add_argument("place", help="city or district name, e.g. 北京 / 深圳 / 杭州 / 海淀")
    parser.add_argument("--limit", type=int, default=10, help="how many candidates to show")
    parser.add_argument("--write", metavar="PATH",
                        help="also write the chosen id to this file (push it to the TV afterwards)")
    parser.add_argument("--key", default="", help="optional open-data API key to put on the same line")
    args = parser.parse_args()

    if not os.path.exists(CODES):
        print("missing {}: cannot look up cities offline".format(CODES), file=sys.stderr)
        return 1
    table = load()

    found = search(table, args.place)
    if not found:
        print("no city found for '{}'".format(args.place), file=sys.stderr)
        print("try the name as written on 中国天气网, e.g. 北京 / 深圳 / 杭州", file=sys.stderr)
        return 1

    print("{} candidate(s) for '{}':".format(len(found), args.place))
    for areaid, province, city, name in found[:args.limit]:
        print("  {:<10} {:<10} {:<10} {}".format(areaid, province, city, name))

    best = found[0]
    line = best[0] + (" " + args.key if args.key else "")
    print("\nput this in weather.txt:\n  {}".format(line))

    if args.write:
        with open(args.write, "w", encoding="utf-8") as handle:
            handle.write(line + "\n")
        print("written to {}".format(args.write))
    return 0


if __name__ == "__main__":
    sys.exit(main())
