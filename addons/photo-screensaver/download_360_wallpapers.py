#!/usr/bin/env python3
"""下载 360 壁纸到本地文件夹, 供 photo-screensaver 的 sync.sh 推送到电视。

360 壁纸公开接口 (无需 Key):
  分类:   http://wallpaper.apc.360.cn/index.php?c=WallPaper&a=getAllCategories
  图片:   http://wallpaper.apc.360.cn/index.php?c=WallPaper&a=getAppsByCategory&cid=<分类>&start=<偏移>&count=<数量>
返回 JSON, 每张图的字段里 `url` 为全分辨率地址 (p9.qhimg.com)。本脚本把 http 改写为 https 下载。

用法:
  python3 download_360_wallpapers.py                 # 默认: 今日推荐分类, 下载 20 张到 ./pictures
  python3 download_360_wallpapers.py --count 30 --category 27
  python3 download_360_wallpapers.py --list-categories
  python3 download_360_wallpapers.py --out ~/Pictures/tv --count 10

下载完后, 用项目自带脚本推送到电视:
  TV_HOST=<电视IP> addons/photo-screensaver/sync.sh <本脚本的 --out 文件夹>
"""
import argparse
import json
import os
import random
import sys
import urllib.request

API = "http://wallpaper.apc.360.cn/index.php"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
MAX_BYTES = 10 * 1024 * 1024   # sync.sh refuses anything bigger


def _get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))


def list_categories():
    data = _get(f"{API}?c=WallPaper&a=getAllCategories")
    cats = data.get("data", {})
    rows = []
    for cid, info in cats.items():
        rows.append((cid, info.get("name", "")))
    rows.sort(key=lambda x: int(x[0]) if x[0].isdigit() else 0)
    for cid, name in rows:
        print(f"  {cid:>4}  {name}")
    return rows


def pick_category_id(category):
    if category is not None:
        return str(category)
    # 默认"今日推荐": 取分类列表里名为"今日推荐"的 id, 找不到则用 27
    try:
        data = _get(f"{API}?c=WallPaper&a=getAllCategories").get("data", {})
        for cid, info in data.items():
            if info.get("name", "") == "今日推荐":
                return str(cid)
    except Exception:
        pass
    return "27"


def download_one(url, dest):
    https = url.replace("http://", "https://", 1)
    req = urllib.request.Request(https, headers={"User-Agent": UA, "Referer": "http://www.360.cn/"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
    with open(dest, "wb") as f:
        f.write(body)
    return len(body)


def main():
    ap = argparse.ArgumentParser(description="下载 360 壁纸到本地 (供电视屏保使用)")
    ap.add_argument("--count", type=int, default=20, help="下载张数 (默认 20)")
    ap.add_argument("--category", type=int, default=None, help="分类 id; 不填=今日推荐")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "pictures"),
                    help="输出文件夹 (默认 ./pictures)")
    ap.add_argument("--list-categories", action="store_true", help="列出可用分类后退出")
    ap.add_argument("--seed", type=int, default=None, help="随机种子 (可复现)")
    args = ap.parse_args()

    if args.list_categories:
        list_categories()
        return

    if args.seed is not None:
        random.seed(args.seed)

    cid = pick_category_id(args.category)
    meta = _get(f"{API}?c=WallPaper&a=getAppsByCategory&cid={cid}&start=0&count=1")
    total = int(meta.get("total", 0))
    if total <= 0:
        sys.exit(f"分类 {cid} 没有可下载的壁纸 (接口返回 total=0)")
    print(f"分类 {cid}: 共 {total} 张, 本次随机取 {args.count} 张")

    # One list call for the whole batch, then download from it: two requests per picture is slow
    # and the interface is easier on a caller that asks less often.
    batch = min(args.count, 50)
    start = random.randint(0, max(0, total - batch))
    page = _get(f"{API}?c=WallPaper&a=getAppsByCategory&cid={cid}&start={start}&count={batch}")
    items = page.get("data") or []
    if not items:
        sys.exit(f"分类 {cid} 在偏移 {start} 处没有返回图片, 换个 --category 试试")

    os.makedirs(args.out, exist_ok=True)
    ok, skip = 0, 0
    for item in items[:args.count]:
        url = (item.get("url") or item.get("url_mid") or item.get("url_thumb") or "").strip()
        if not url:
            skip += 1
            continue
        name = f"360_{cid}_{start + ok}.jpg"
        dest = os.path.join(args.out, name)
        try:
            size = download_one(url, dest)
        except Exception as e:
            print(f"  下载失败 {name}: {e}")
            skip += 1
            continue
        if size > MAX_BYTES:
            # sync.sh refuses files over 10 MB, so do not leave one behind.
            os.remove(dest)
            print(f"  跳过 {name}: {size // 1024 // 1024} MB, 超过 sync.sh 的 10 MB 上限")
            skip += 1
            continue
        ok += 1
        print(f"  [{ok}/{args.count}] {name}  ({size // 1024} KB)")
    print(f"完成: 成功 {ok} 张, 跳过 {skip} 张 -> {args.out}")
    print(f"推送到电视: TV_HOST=<电视IP> addons/photo-screensaver/sync.sh {args.out}")


if __name__ == "__main__":
    main()
