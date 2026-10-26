-- =====================================================================
-- bi_warehouse 电商 BI 数仓建模（生产级 DDL）
-- 维度建模：2 张主维度表 + 1 张活动维度 + 3 张事实表
-- 设计要点（生产规范）：
--   1. 事实表不建物理外键（大表 FK 锁与维护成本高），用索引 + 上游校验保证一致性
--   2. 所有表/字段必须 COMMENT（NL2SQL schema-linking 依赖注释做语义召回）
--   3. 事实表按查询模式建复合索引（日期+区域 / 日期+渠道 覆盖高频 BI 查询）
--   4. 敏感字段（手机号）入仓即脱敏
-- =====================================================================
USE bi_warehouse;

-- ---------------------------- 维度表 ----------------------------
CREATE TABLE IF NOT EXISTS dim_users (
    user_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
    user_name      VARCHAR(64)  NOT NULL COMMENT '用户昵称（已脱敏）',
    gender         TINYINT      NOT NULL DEFAULT 0 COMMENT '性别：0=未知 1=男 2=女',
    age_band       VARCHAR(16)  NOT NULL DEFAULT 'unknown' COMMENT '年龄段：18-24/25-30/31-40/41-50/50+/unknown',
    province       VARCHAR(32)  NOT NULL COMMENT '省份',
    city           VARCHAR(32)  NOT NULL COMMENT '城市',
    region         VARCHAR(32)  NOT NULL COMMENT '销售大区：east=华东 south=华南 north=华北 central=华中 west=西部 northeast=东北',
    vip_level      TINYINT      NOT NULL DEFAULT 0 COMMENT '会员等级：0=普通 1=银卡 2=金卡 3=铂金 4=钻石',
    register_date  DATE         NOT NULL COMMENT '注册日期',
    user_phone     VARCHAR(16)  DEFAULT NULL COMMENT '手机号（脱敏：保留前3后4）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入仓时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (user_id),
    KEY idx_users_region (region),
    KEY idx_users_register (register_date),
    KEY idx_users_vip (vip_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户维度表：注册用户主数据，按注册时点记录画像';

CREATE TABLE IF NOT EXISTS dim_products (
    sku_id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品SKU ID，主键',
    sku_name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
    category_l1    VARCHAR(32)   NOT NULL COMMENT '一级类目：数码家电/服饰鞋包/美妆个护/食品饮料/家居日用/母婴玩具/运动户外/图书文娱',
    category_l2    VARCHAR(32)   NOT NULL COMMENT '二级类目（如 手机/笔记本/连衣裙）',
    brand          VARCHAR(64)   NOT NULL COMMENT '品牌',
    list_price     DECIMAL(10,2) NOT NULL COMMENT '吊牌价，单位元',
    cost_price     DECIMAL(10,2) NOT NULL COMMENT '进货成本价，单位元',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '商品状态：1=在售 0=下架',
    launch_date    DATE          NOT NULL COMMENT '上架日期',
    PRIMARY KEY (sku_id),
    KEY idx_products_l1 (category_l1),
    KEY idx_products_brand (brand),
    KEY idx_products_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品维度表：SKU 主数据，含类目/品牌/价格带';

CREATE TABLE IF NOT EXISTS dim_campaigns (
    campaign_id    INT          NOT NULL AUTO_INCREMENT COMMENT '营销活动ID，主键',
    campaign_name  VARCHAR(64)  NOT NULL COMMENT '活动名称，如 618大促/双11狂欢节',
    campaign_type  VARCHAR(32)  NOT NULL COMMENT '活动类型：platform_promo=平台大促 category_promo=品类日 member_day=会员日',
    start_date     DATE         NOT NULL COMMENT '活动开始日期',
    end_date       DATE         NOT NULL COMMENT '活动结束日期',
    discount_desc  VARCHAR(128) DEFAULT NULL COMMENT '优惠说明，如 满300减50',
    PRIMARY KEY (campaign_id),
    KEY idx_campaign_dates (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营销活动维度表：平台大促与品类日活动日历';

-- ---------------------------- 事实表 ----------------------------
CREATE TABLE IF NOT EXISTS fact_orders (
    order_id        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID，主键',
    order_date      DATE          NOT NULL COMMENT '下单日期（BI 分区统计口径字段）',
    user_id         BIGINT        NOT NULL COMMENT '下单用户ID，关联 dim_users.user_id',
    channel         VARCHAR(16)   NOT NULL COMMENT '下单渠道：app=手机APP web=官网 mini=微信小程序 offline=线下门店 third=第三方平台',
    region          VARCHAR(16)   NOT NULL COMMENT '收货大区（下单时快照）：east/south/north/central/west/northeast',
    campaign_id     INT           DEFAULT NULL COMMENT '参与的营销活动ID，关联 dim_campaigns.campaign_id，非活动单为NULL',
    gmv             DECIMAL(18,2) NOT NULL COMMENT '订单应付金额(GMV)，单位元',
    discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '优惠总额（活动+券），单位元',
    order_status    TINYINT       NOT NULL COMMENT '订单状态：0=待支付 1=已支付 2=已发货 3=已完成 4=已取消 5=已退款',
    pay_time        DATETIME      DEFAULT NULL COMMENT '支付时间，未支付/取消为NULL',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入仓时间',
    PRIMARY KEY (order_id),
    KEY idx_orders_date (order_date),
    KEY idx_orders_user (user_id),
    KEY idx_orders_date_region (order_date, region),
    KEY idx_orders_date_channel (order_date, channel),
    KEY idx_orders_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事实表：一笔交易一行，BI 销售分析核心宽表';

CREATE TABLE IF NOT EXISTS fact_order_items (
    item_id      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID，主键',
    order_id     BIGINT        NOT NULL COMMENT '订单ID，关联 fact_orders.order_id',
    sku_id       BIGINT        NOT NULL COMMENT '商品SKU ID，关联 dim_products.sku_id',
    quantity     INT           NOT NULL COMMENT '购买数量',
    unit_price   DECIMAL(10,2) NOT NULL COMMENT '成交单价（折后），单位元',
    sales_amount DECIMAL(18,2) NOT NULL COMMENT '销售小计 = quantity * unit_price，单位元',
    PRIMARY KEY (item_id),
    KEY idx_items_order (order_id),
    KEY idx_items_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细事实表：一个订单下每个商品一行，用于商品维度分析';

CREATE TABLE IF NOT EXISTS fact_refunds (
    refund_id     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '退款单ID，主键',
    order_id      BIGINT        NOT NULL COMMENT '关联 fact_orders.order_id',
    item_id       BIGINT        DEFAULT NULL COMMENT '关联 fact_order_items.item_id，整单退款为NULL',
    refund_date   DATE          NOT NULL COMMENT '退款申请日期',
    refund_amount DECIMAL(18,2) NOT NULL COMMENT '退款金额，单位元',
    refund_reason VARCHAR(64)   NOT NULL COMMENT '退款原因：质量问题/七天无理由/物流损坏/少发漏发/不想要了',
    refund_status TINYINT       NOT NULL COMMENT '退款状态：0=待审核 1=已退款 2=已拒绝',
    PRIMARY KEY (refund_id),
    KEY idx_refunds_date (refund_date),
    KEY idx_refunds_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款事实表：售后逆向数据，用于退款率与售后质量分析';
