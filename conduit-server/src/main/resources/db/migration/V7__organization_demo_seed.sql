-- 只为 V4 的四个演示租户补充组织；不修改历史迁移，不向普通新租户灌入演示业务数据。
-- 固定 demo-* 编码用于跨电脑复现。已有同编码对象不覆盖，已有人工设备归属不覆盖。
create temporary table organization_demo_seed (
    tenant_code text, customer_code text, customer_name text,
    project_code text, project_name text, site_code text, site_name text, ordinal integer
) on commit drop;

insert into organization_demo_seed
select t.code, 'demo-customer-' || p, t.customer_names[p],
       'demo-project-' || p, t.project_names[p],
       'demo-site-' || ((p - 1) * 2 + s), t.site_names[(p - 1) * 2 + s], (p - 1) * 2 + s
from (values
    ('default', array['智创园区运营公司','滨江物业服务公司'], array['智创园区综合能源','滨江楼宇环境监测'],
        array['智创园区一期','智创园区二期','滨江一号楼','滨江二号楼']),
    ('huadong-power', array['华东制造集团','江南装备公司'], array['华东工厂配电运维','江南变压器监测'],
        array['华东一号配电站','华东二号配电站','江南一号变电站','江南二号变电站']),
    ('huanan-water', array['海岸社区服务中心','翠湖供水公司'], array['海岸社区智慧供水','翠湖水质保障'],
        array['海岸供水分区','碧桂供水分区','翠湖一号加压站','翠湖二号加压站']),
    ('xibei-energy', array['西北绿能公司','戈壁新能源公司'], array['西北光伏示范电站','戈壁光储监测'],
        array['西北光伏东区','西北光伏西区','戈壁光伏一区','戈壁光伏二区'])
) t(code, customer_names, project_names, site_names)
cross join generate_series(1, 2) p cross join generate_series(1, 2) s;

insert into customer (tenant_id, code, name)
select distinct t.id, s.customer_code, s.customer_name
from organization_demo_seed s join tenant t on t.code=s.tenant_code
on conflict (tenant_id, code) do nothing;

insert into project (tenant_id, customer_id, code, name)
select distinct t.id, c.id, s.project_code, s.project_name
from organization_demo_seed s join tenant t on t.code=s.tenant_code
join customer c on c.tenant_id=t.id and c.code=s.customer_code
on conflict (tenant_id, code) do nothing;

insert into site (tenant_id, project_id, code, name, timezone)
select t.id, p.id, s.site_code, s.site_name, 'Asia/Shanghai'
from organization_demo_seed s join tenant t on t.code=s.tenant_code
join project p on p.tenant_id=t.id and p.code=s.project_code
on conflict (tenant_id, code) do nothing;

insert into asset (tenant_id, site_id, code, name, type)
select t.id, si.id, s.site_code || '-area', s.site_name || '设备区', 'AREA'
from organization_demo_seed s join tenant t on t.code=s.tenant_code
join site si on si.tenant_id=t.id and si.code=s.site_code
on conflict (tenant_id, code) do nothing;

insert into asset (tenant_id, site_id, parent_id, code, name, type, status)
select t.id, si.id, parent.id, s.site_code || a.suffix, a.name, a.type, a.status
from organization_demo_seed s join tenant t on t.code=s.tenant_code
join site si on si.tenant_id=t.id and si.code=s.site_code
join asset parent on parent.tenant_id=t.id and parent.site_id=si.id and parent.code=s.site_code || '-area'
cross join (values ('-main','主设备组','EQUIPMENT_GROUP','ACTIVE'),
                   ('-reserve','备用设备组','EQUIPMENT_GROUP','DISABLED')) a(suffix, name, type, status)
on conflict (tenant_id, code) do nothing;

-- 仅识别 V4 演示命名的设备，兼容旧环境同名不同 key 的演示设备。
-- 仅移动尚在系统默认站点且未分配资产的设备，保留人工归属及设备身份、密钥、启停状态。
with candidates as (
    select d.id, d.tenant_id, t.code tenant_code,
           row_number() over (partition by d.tenant_id order by p.product_key, d.device_key) rn
    from device d join tenant t on t.id=d.tenant_id
    join product p on p.id=d.product_id and p.tenant_id=d.tenant_id
    join site old_site on old_site.id=d.site_id and old_site.tenant_id=d.tenant_id
    where old_site.is_default and d.asset_id is null and (
        (t.code='default' and p.name='三相电表（演示）' and d.name ~ '^[0-9]+号楼配电室 电表[0-9]+$') or
        (t.code='default' and p.name='环境传感器' and d.name ~ '^[0-9]+号楼[0-9]+层 环境监测[0-9]+$') or
        (t.code='huadong-power' and p.name='三相电表（华东）' and d.name ~ '^[0-9]+号配电室 电表[0-9]+$') or
        (t.code='huadong-power' and p.name='变压器温控器' and d.name ~ '^[0-9]+号变压器 温控器$') or
        (t.code='huanan-water' and p.name='智能水表' and d.name ~ '^(海岸小区|碧桂小区|翠湖小区) 水表[0-9]+$') or
        (t.code='huanan-water' and p.name='水质监测仪' and d.name ~ '^[0-9]+号加压站 水质监测$') or
        (t.code='xibei-energy' and p.name='光伏逆变器' and d.name ~ '^[0-9]+号光伏阵列 逆变器[0-9]+$') or
        (t.code='xibei-energy' and p.name='气象站' and d.name in ('东区 气象站','西区 气象站'))
    )
)
update device d set site_id=si.id, asset_id=a.id
from candidates c join organization_demo_seed s on s.tenant_code=c.tenant_code and s.ordinal=(c.rn - 1) % 4 + 1
join site si on si.tenant_id=c.tenant_id and si.code=s.site_code
join asset a on a.tenant_id=c.tenant_id and a.site_id=si.id and a.code=s.site_code || '-main'
where d.id=c.id;
