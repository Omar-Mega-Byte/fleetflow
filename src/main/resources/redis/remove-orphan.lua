-- Only remove GEO membership while latest location is absent, atomically.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return redis.call('ZREM', KEYS[2], ARGV[1])
end
return 0
