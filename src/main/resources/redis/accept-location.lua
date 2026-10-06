-- Result: 0 accepted, 1 old/equal fix, 2 rate limited.
-- Compare before touching rate/liveness. All accepted state changes are atomic.
local oldSeconds = redis.call('HGET', KEYS[1], 'reportedSeconds')
local oldNanos = redis.call('HGET', KEYS[1], 'reportedNanos')
if oldSeconds then
    if tonumber(ARGV[1]) < tonumber(oldSeconds) or
       (tonumber(ARGV[1]) == tonumber(oldSeconds) and tonumber(ARGV[2]) <= tonumber(oldNanos)) then
        return 1
    end
end
local count = redis.call('INCR', KEYS[4])
if count == 1 then redis.call('PEXPIRE', KEYS[4], ARGV[12]) end
if count > tonumber(ARGV[11]) then return 2 end
redis.call('HSET', KEYS[1], 'reportedSeconds', ARGV[1], 'reportedNanos', ARGV[2],
    'receivedAtMillis', ARGV[3], 'latitude', ARGV[4], 'longitude', ARGV[5],
    'heading', ARGV[6], 'speed', ARGV[7])
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[9])
-- Redis GEO uses longitude, latitude in that order.
redis.call('GEOADD', KEYS[3], ARGV[5], ARGV[13], ARGV[10])
return 0
