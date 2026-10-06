-- Result: 0 accepted, 2 rate limited; never touches location or GEO.
local count = redis.call('INCR', KEYS[2])
if count == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[4]) end
if count > tonumber(ARGV[3]) then return 2 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return 0
