package uk.co.real_logic.aeron.tools;

import uk.co.real_logic.aeron.tools.RateController.IntervalInternal;

public class SecondsAtBitsPerSecondInterval extends RateControllerInterval
{
	/* The rate we _want_ to achieve, if possible.  Might not be able
	 * to hit it exactly due to receiver pacing, etc.  But it's what
	 * we're aiming for. */
	private final long goalBitsPerSecond;
	/* Number of seconds (can be fractional) to run for, in total. */
	private final double seconds;

	public SecondsAtBitsPerSecondInterval(double seconds, long bitsPerSecond)
	{
		this.goalBitsPerSecond = bitsPerSecond;
		this.seconds = seconds;
	}

	@Override
	IntervalInternal makeInternal(RateController rateController)
	{
		return rateController.new SecondsAtBitsPerSecondInternal(rateController, seconds, goalBitsPerSecond);
	}
}
