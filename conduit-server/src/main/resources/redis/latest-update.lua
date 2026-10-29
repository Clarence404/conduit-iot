-- KEYS[1] = conduit:latest:{deviceKey}
-- ARGV[1] = 本次数据的 ts（毫秒）
-- ARGV[2..] = 测点名, {"v":...,"ts":...} 交替
-- 逐测点比较 ts：只有不早于当前值的数据才覆盖，补传的旧数据不会盖掉新值。
--
-- 快路径：__last 是该设备见过的最大 ts，而每个测点存的 ts 必然 ≤ __last
-- （测点只会被写入当时那条消息的 ts，而该 ts 当时就 ≤ __last）。
-- 所以 ts >= __last 时，不可能有哪个测点比本条更新，逐测点比较纯属多余，
-- 可以直接整批 HSET。顺序到达是绝对主流，这条路径覆盖了几乎全部流量：
-- 常见情况由「每测点 1 次 HGET + 1 次 cjson.decode + 1 次 HSET」降为「1 次 HSET」。
-- 乱序或补传的数据（ts < __last）仍走原来的逐测点比较，语义完全不变。
local ts = tonumber(ARGV[1])
local last = redis.call('HGET', KEYS[1], '__last')

if last and ts < tonumber(last) then
  -- 慢路径：本条不是最新的，必须逐测点比较，避免旧数据盖掉新值
  local updated = 0
  for i = 2, #ARGV, 2 do
    local current = redis.call('HGET', KEYS[1], ARGV[i])
    if (not current) or ts >= tonumber(cjson.decode(current)['ts']) then
      redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
      updated = updated + 1
    end
  end
  return updated
end

-- 快路径：ts >= __last（或设备尚无数据），本条即最新，全部测点直接覆盖
local updated = 0
if #ARGV >= 3 then
  -- unpack 受 Lua 栈大小限制，测点极多的设备会报 "too many results to unpack"，
  -- 所以只在参数不多时走一次 HSET，否则逐个写（仍然省掉了 HGET 与 cjson.decode）
  if #ARGV <= 200 then
    redis.call('HSET', KEYS[1], unpack(ARGV, 2))
  else
    for i = 2, #ARGV, 2 do
      redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
    end
  end
  updated = (#ARGV - 1) / 2
end
redis.call('HSET', KEYS[1], '__last', ARGV[1])
return updated
