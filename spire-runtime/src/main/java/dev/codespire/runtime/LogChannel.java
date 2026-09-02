package dev.codespire.runtime;

/**
 * Which container's output is being read.
 *
 * <p>A run has two log streams that mean different things, and conflating them loses the
 * distinction that matters: {@link #AGENT} is what the model did, and is untrusted text the agent
 * itself can write; {@link #PUBLISHER} is what the gate decided, and is the run's audit trail.
 */
public enum LogChannel { AGENT, PUBLISHER }
