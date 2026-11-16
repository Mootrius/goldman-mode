package com.goldmanmode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CountUpTest
{
	@Test
	public void firstValueShowsAtOnce()
	{
		CountUp c = new CountUp();
		c.update(5_000, 1_000);
		assertEquals(5_000, c.current(1_000));
	}

	@Test
	public void anIncreaseTicksUpOverSixTenthsOfASecond()
	{
		CountUp c = new CountUp();
		c.update(1_000, 0);
		c.update(2_000, 10_000);
		assertEquals(1_000, c.current(10_000));
		assertEquals(1_500, c.current(10_300));
		assertEquals(2_000, c.current(10_600));
		assertEquals(2_000, c.current(12_000));
	}

	@Test
	public void aSecondGainKeepsClimbingFromWhereItWas()
	{
		CountUp c = new CountUp();
		c.update(0, 0);
		c.update(1_000, 1_000);
		assertEquals(500, c.current(1_300));
		c.update(1_500, 1_300); // another gain halfway: continue from 500 up to 1,500
		assertEquals(500, c.current(1_300));
		assertEquals(1_000, c.current(1_600));
		assertEquals(1_500, c.current(1_900));
	}

	@Test
	public void aDecreaseAndAResetShowAtOnce()
	{
		CountUp c = new CountUp();
		c.update(10_000, 0);
		c.update(4_000, 100);
		assertEquals(4_000, c.current(100));
		c.reset();
		c.update(9_000, 200);
		assertEquals(9_000, c.current(200));
	}
}
