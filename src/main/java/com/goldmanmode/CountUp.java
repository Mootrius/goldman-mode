package com.goldmanmode;

/**
 * A number that ticks up to its new value instead of jumping. Every increase takes {@link #DURATION_MS} to arrive,
 * starting from whatever is on screen at that moment (so quick successive gains just keep it climbing).
 * The first value, and any decrease, show at once.
 */
final class CountUp
{
	static final long DURATION_MS = 600;

	private boolean started;
	private double from;
	private long target;
	private long startedAt;

	/** Tell it the real value; call every frame (it only reacts when the value changed). */
	synchronized void update(long value, long nowMs)
	{
		if (!started || value < target)
		{
			started = true;
			from = value;
			target = value;
			startedAt = nowMs;
		}
		else if (value > target)
		{
			from = shown(nowMs);
			target = value;
			startedAt = nowMs;
		}
	}

	/** The value to draw now. */
	synchronized long current(long nowMs)
	{
		return Math.round(shown(nowMs));
	}

	/** Forget everything, so the next value shows at once (after a logout, say). */
	synchronized void reset()
	{
		started = false;
	}

	private double shown(long nowMs)
	{
		if (!started)
		{
			return 0;
		}
		double t = (nowMs - startedAt) / (double) DURATION_MS;
		if (t >= 1)
		{
			return target;
		}
		if (t <= 0)
		{
			return from;
		}
		return from + (target - from) * t;
	}
}
