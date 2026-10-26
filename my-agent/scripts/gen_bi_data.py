#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bi_warehouse 生产级测试数据生成器
====================================
目标：生成"像真的一样"的电商 BI 数仓数据，而不是随机噪声。
生产级特征：
  1. 时间结构：整体增长趋势 + 周内季节性（周末高） + 大促脉冲（618/双11/双12/年货节/818）
  2. 分布真实：用户活跃度遵循 80/20（少数用户贡献多数订单）、地区/渠道有稳定权重、
     金额呈对数正态、退款与大促负相关（大促履约质量反而下降）
  3. 一致性：订单日期 >= 用户注册日期；明细金额 = 数量*单价；退款关联已支付订单；
     支付时间晚于下单时间；订单状态机合法（未支付无 pay_time）
  4. 可复现：固定随机种子，两次生成结果一致（评测 golden set 依赖确定性）
用法：python3 scripts/gen_bi_data.py > deploy/infra/mysql/init/02_data.sql
"""
import random
from datetime import date, datetime, timedelta

random.seed(20261023)

START = date(2024, 10, 1)
END = date(2026, 9, 30)

# ---------- 业务字典（生产感：真实类目/品牌/城市结构） ----------
CATEGORIES = {
    "数码家电": {"l2": ["手机", "笔记本", "平板", "耳机", "智能手表", "电视机"],
                "brands": ["星驰", "蓝鲸", "极光", "天穹", "迅羽"],
                "price": (199, 8999)},
    "服饰鞋包": {"l2": ["连衣裙", "T恤", "牛仔裤", "运动鞋", "双肩包", "羽绒服"],
                "brands": ["衣澜", "步履", "山河行", "薄荷巷", "简白"],
                "price": (49, 1299)},
    "美妆个护": {"l2": ["精华液", "面膜", "洗发水", "口红", "防晒霜"],
                "brands": ["肌研所", "花拾", "素研", "初颜"],
                "price": (39, 599)},
    "食品饮料": {"l2": ["坚果礼盒", "咖啡豆", "酸奶", "休闲零食", "茶叶"],
                "brands": ["山禾", "鲜果集", "谷语", "一口田"],
                "price": (19, 299)},
    "家居日用": {"l2": ["四件套", "收纳箱", "香薰", "保温杯", "台灯"],
                "brands": ["栖居", "白屋", "暖山", "素舍"],
                "price": (29, 899)},
    "母婴玩具": {"l2": ["婴儿推车", "积木", "绘本", "奶瓶", "儿童安全座椅"],
                "brands": ["小雏菊", "贝乐园", "萌芽", "童梦"],
                "price": (59, 2999)},
    "运动户外": {"l2": ["瑜伽垫", "跑步鞋", "帐篷", "哑铃", "骑行头盔"],
                "brands": ["野径", "峰行", "跃动", "风掣"],
                "price": (59, 1999)},
    "图书文娱": {"l2": ["小说", "少儿科普", "漫画", "文具套装"],
                "brands": ["纸间", "青阅", "墨点"],
                "price": (15, 199)},
}
# 大区 -> (权重, 省份列表[(省, 市, 省内权重)])
REGION_CITY = {
    "east": (0.32, [("江苏省", "南京市", .3), ("江苏省", "苏州市", .3), ("上海市", "上海市", .25), ("浙江省", "杭州市", .15)]),
    "south": (0.22, [("广东省", "广州市", .35), ("广东省", "深圳市", .4), ("福建省", "厦门市", .25)]),
    "north": (0.18, [("北京市", "北京市", .5), ("天津市", "天津市", .2), ("河北省", "石家庄市", .3)]),
    "central": (0.12, [("湖北省", "武汉市", .5), ("湖南省", "长沙市", .3), ("河南省", "郑州市", .2)]),
    "west": (0.09, [("四川省", "成都市", .5), ("重庆市", "重庆市", .3), ("陕西省", "西安市", .2)]),
    "northeast": (0.07, [("辽宁省", "沈阳市", .4), ("吉林省", "长春市", .3), ("黑龙江省", "哈尔滨市", .3)]),
}
CHANNELS = [("app", .45), ("mini", .25), ("web", .12), ("offline", .10), ("third", .08)]
GENDERS = [(1, .48), (2, .44), (0, .08)]
AGE_BANDS = [("18-24", .18), ("25-30", .27), ("31-40", .30), ("41-50", .15), ("50+", .10)]
VIP_LEVELS = [(0, .55), (1, .22), (2, .13), (3, .07), (4, .03)]
# 状态机：电商稳态分布（待支付极少、完成是大头、退款 5%）
ORDER_STATUS = [(1, .10), (2, .15), (3, .63), (4, .07), (5, .05)]
REFUND_REASONS = [("质量问题", .22), ("七天无理由", .34), ("物流损坏", .14), ("少发漏发", .10), ("不想要了", .20)]
REFUND_STATUS = [(1, .85), (0, .10), (2, .05)]

SURNAMES = list("王李张刘陈杨黄赵周吴徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董潘袁蔡蒋余于杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
GIVEN = ["雨桐", "子墨", "浩然", "欣怡", "梓萱", "俊杰", "思远", "嘉怡", "宇航", "梦琪", "博文", "雪儿", "天佑", "一诺", "佳琪", "铭轩", "晓彤", "紫涵", "志强", "丽娜", "建国", "秀英", "志明", "玉梅"]

CAMPAIGNS = [
    # (id, name, type, start, end, discount)
    (1, "2025年货节", "platform_promo", date(2025, 1, 15), date(2025, 1, 25), "满300减50"),
    (2, "2025年618大促", "platform_promo", date(2025, 6, 1), date(2025, 6, 20), "满300减50、跨店每满300减50"),
    (3, "2025年818会员日", "member_day", date(2025, 8, 15), date(2025, 8, 25), "会员95折叠加满减"),
    (4, "2025年双11狂欢节", "platform_promo", date(2025, 11, 1), date(2025, 11, 11), "每满300减50、前N名半价"),
    (5, "2025年双12盛典", "platform_promo", date(2025, 12, 1), date(2025, 12, 12), "满200减30"),
    (6, "2026年货节", "platform_promo", date(2026, 1, 15), date(2026, 1, 25), "满300减50"),
    (7, "2026年618大促", "platform_promo", date(2026, 6, 1), date(2026, 6, 20), "满300减60、跨店每满300减50"),
    (8, "2026年818会员日", "member_day", date(2026, 8, 15), date(2026, 8, 25), "会员88折"),
]
# 大促脉冲强度：活动日期 -> 订单量倍率（峰值日 3.2x）
def promo_factor(d: date) -> float:
    for cid, name, ctype, s, e, disc in CAMPAIGNS:
        if s <= d <= e:
            span = (e - s).days or 1
            pos = (d - s).days
            # 起伏曲线：中后段冲峰（模拟双11当天爆发）
            return 1.8 + 1.4 * (pos / span) ** 2
    return 1.0

def weighted(pairs):
    x = random.random()
    acc = 0.0
    for v, w in pairs:
        acc += w
        if x <= acc:
            return v
    return pairs[-1][0]

def lognorm_price(lo, hi):
    # 对数正态：多数订单金额偏低，长尾大额
    import math
    mu = math.log((lo * hi) ** 0.5)
    sigma = 0.9
    p = math.exp(random.gauss(mu, sigma))
    return max(lo, min(hi, p))

def pick_city(region):
    cities = REGION_CITY[region][1]
    return weighted([(f"{p}|{c}", w) for p, c, w in cities]).split("|")

# ============================ 生成 ============================
out = []
out.append("-- 02_data.sql：生产级仿真数据（由 scripts/gen_bi_data.py 确定性生成，勿手改）")
out.append("SET NAMES utf8mb4;")
out.append("USE bi_warehouse;")
out.append("SET autocommit=0;")

# ---- dim_campaigns ----
out.append("INSERT INTO dim_campaigns (campaign_id, campaign_name, campaign_type, start_date, end_date, discount_desc) VALUES")
rows = [f"({cid},'{name}','{ctype}','{s}','{e}','{disc}')" for cid, name, ctype, s, e, disc in CAMPAIGNS]
out.append(",\n".join(rows) + ";")

# ---- dim_products：800 SKU ----
N_PRODUCTS = 800
out.append("INSERT INTO dim_products (sku_id, sku_name, category_l1, category_l2, brand, list_price, cost_price, status, launch_date) VALUES")
sku_rows, sku_price = [], {}
for sku_id in range(1, N_PRODUCTS + 1):
    l1 = weighted([(k, 1 / len(CATEGORIES)) for k in CATEGORIES])
    meta = CATEGORIES[l1]
    l2 = random.choice(meta["l2"])
    brand = random.choice(meta["brands"])
    lo, hi = meta["price"]
    list_price = round(lognorm_price(lo, hi), 2)
    cost_price = round(list_price * random.uniform(0.45, 0.72), 2)
    status = 1 if random.random() > 0.06 else 0
    launch = START - timedelta(days=random.randint(30, 900))
    name = f"{brand} {l2}"
    if sku_id % 3 == 0:
        name += f" {random.choice(['经典款', 'Pro版', '青春版', '限量款', '2025新款'])}"
    sku_rows.append(f"({sku_id},'{name}','{l1}','{l2}','{brand}',{list_price},{cost_price},{status},'{launch}')")
    sku_price[sku_id] = list_price
out.append(",\n".join(sku_rows) + ";")

# ---- dim_users：5000 用户 ----
N_USERS = 5000
out.append("INSERT INTO dim_users (user_id, user_name, gender, age_band, province, city, region, vip_level, register_date, user_phone) VALUES")
user_rows = []
user_meta = {}  # user_id -> (region, register_date, vip)
for uid in range(1, N_USERS + 1):
    region = weighted([(r, w) for r, (w, _) in REGION_CITY.items()])
    pv, ct = pick_city(region)
    gender = weighted(GENDERS)
    age = weighted(AGE_BANDS)
    vip = weighted(VIP_LEVELS)
    name = f"用户{random.choice(SURNAMES)}{random.choice(GIVEN)}"
    reg = START - timedelta(days=random.randint(1, 1200))
    phone = f"1{random.choice(['3','5','7','8','9'])}{random.randint(0,9999):04d}****{random.randint(0,9999):04d}"
    user_rows.append(f"({uid},'{name}',{gender},'{age}','{pv}','{ct}','{region}',{vip},'{reg}','{phone}')")
    user_meta[uid] = (region, reg, vip)
out.append(",\n".join(user_rows) + ";")

# 活跃用户池：80/20 —— 20% 的活跃用户贡献约 80% 的订单
hot_users = random.sample(range(1, N_USERS + 1), N_USERS // 5)

# ---- fact_orders + fact_order_items ----
def order_volume(d: date) -> int:
    days = (d - START).days
    total = (END - START).days
    base = 60 * (1 + 0.45 * days / total)          # 增长趋势 +45%
    base *= 1.15 if d.weekday() >= 5 else 0.93      # 周末季节性
    base *= promo_factor(d)
    base *= 1 + random.uniform(-0.06, 0.06)         # 日噪声
    # 春节低谷（2月上旬）
    if d.month == 2 and d.day <= 10:
        base *= 0.55
    return int(base)

order_rows, item_rows, refund_rows = [], [], []
order_id = 0
item_id = 0
d = START
while d <= END:
    # 当日订单量
    n = order_volume(d)
    # 当日大促活动
    camp = None
    for cid, name, ctype, s, e, disc in CAMPAIGNS:
        if s <= d <= e and random.random() < 0.55:
            camp = cid
            break
    for _ in range(n):
        order_id += 1
        # 80/20 用户抽样
        uid = random.choice(hot_users) if random.random() < 0.8 else random.randint(1, N_USERS)
        _, reg_date, _ = user_meta[uid]
        if reg_date > d:  # 注册前的订单不允许：改用老用户
            uid = random.choice([u for u in range(1, N_USERS + 1) if user_meta[u][1] <= d] or [1])
        region = user_meta[uid][0]
        channel = weighted(CHANNELS)
        # 金额：与类目挂钩 → 先抽 1~4 个商品
        n_items = weighted([(1, .45), (2, .32), (3, .16), (4, .07)])
        gmv, discount = 0.0, 0.0
        picked = []
        for _ in range(n_items):
            sku = random.randint(1, N_PRODUCTS)
            qty = weighted([(1, .75), (2, .18), (3, .07)])
            unit = round(sku_price[sku] * (random.uniform(0.75, 1.0) if camp else random.uniform(0.95, 1.0)), 2)
            amt = round(unit * qty, 2)
            gmv += amt
            discount += round(sku_price[sku] * qty - amt, 2)
            picked.append((sku, qty, unit, amt))
        gmv = round(gmv + random.uniform(0, 12), 2)  # 运费
        discount = round(min(discount, gmv * 0.4), 2)
        status = weighted(ORDER_STATUS)
        pay_time = "NULL"
        if status >= 1:
            pay_time = f"'{datetime(d.year, d.month, d.day, random.randint(0, 23), random.randint(0, 59))}'"
        order_rows.append(
            f"({order_id},'{d}',{uid},'{channel}','{region}',{camp or 'NULL'},{gmv},{discount},{status},{pay_time})")
        # 明细
        for sku, qty, unit, amt in picked:
            item_id += 1
            item_rows.append(f"({item_id},{order_id},{sku},{qty},{unit},{amt})")
        # 退款：状态=5 的订单生成退款记录
        if status == 5:
            rid = len(refund_rows) + 1
            reason = weighted(REFUND_REASONS)
            rstatus = weighted(REFUND_STATUS)
            rdate = d + timedelta(days=random.randint(1, 10))
            if rdate > END:
                rdate = END
            amt = round(gmv * random.uniform(0.5, 1.0), 2)
            refund_rows.append(f"({rid},{order_id},NULL,'{rdate}',{amt},'{reason}',{rstatus})")
    d += timedelta(days=1)

out.append("INSERT INTO fact_orders (order_id, order_date, user_id, channel, region, campaign_id, gmv, discount_amount, order_status, pay_time) VALUES")
HDR_ORDERS = out.pop()
for i in range(0, len(order_rows), 500):
    out.append(HDR_ORDERS)
    out.append(",\n".join(order_rows[i:i + 500]) + ";")

out.append("INSERT INTO fact_order_items (item_id, order_id, sku_id, quantity, unit_price, sales_amount) VALUES")
HDR_ITEMS = out.pop()
for i in range(0, len(item_rows), 500):
    out.append(HDR_ITEMS)
    out.append(",\n".join(item_rows[i:i + 500]) + ";")

out.append("INSERT INTO fact_refunds (refund_id, order_id, item_id, refund_date, refund_amount, refund_reason, refund_status) VALUES")
HDR_REFUNDS = out.pop()
for i in range(0, len(refund_rows), 500):
    out.append(HDR_REFUNDS)
    out.append(",\n".join(refund_rows[i:i + 500]) + ";")

out.append("COMMIT;")
out.append("-- 统计")
out.append("SELECT 'dim_campaigns' t, COUNT(*) c FROM dim_campaigns UNION ALL SELECT 'dim_products', COUNT(*) FROM dim_products UNION ALL SELECT 'dim_users', COUNT(*) FROM dim_users UNION ALL SELECT 'fact_orders', COUNT(*) FROM fact_orders UNION ALL SELECT 'fact_order_items', COUNT(*) FROM fact_order_items UNION ALL SELECT 'fact_refunds', COUNT(*) FROM fact_refunds;")

import sys
sys.stdout.write("\n".join(out) + "\n")
print(f"-- orders={len(order_rows)} items={len(item_rows)} refunds={len(refund_rows)}", file=sys.stderr)
