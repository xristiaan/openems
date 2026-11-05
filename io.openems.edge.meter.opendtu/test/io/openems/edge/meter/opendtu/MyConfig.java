package io.openems.edge.meter.opendtu;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.types.MeterType;
import io.openems.edge.common.type.Phase.SinglePhase;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String ipAddress;
		private String serialNumber;
		private MeterType type;
		private SinglePhase phase;
		private boolean controlLimit = false;
		private String password = "";

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setIp(String ip) {
			this.ipAddress = ip;
			return this;
		}

		public Builder setSerialNumber(String serialNumber) {
			this.serialNumber = serialNumber;
			return this;
		}

		public Builder setType(MeterType type) {
			this.type = type;
			return this;
		}

		public Builder setPhase(SinglePhase phase) {
			this.phase = phase;
			return this;
		}

		public Builder setControlLimit(boolean controlLimit) {
			this.controlLimit = controlLimit;
			return this;
		}

		public Builder setPassword(String password) {
			this.password = password;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String ipAddress() {
		return this.builder.ipAddress;
	}

	@Override
	public SinglePhase phase() {
		return this.builder.phase;
	}

	@Override
	public String serialNumber() {
		return this.builder.serialNumber;
	}

	@Override
	public boolean controlLimit() {
		return this.builder.controlLimit;
	}

	@Override
	public String username() {
		return "admin";
	}

	@Override
	public String password() {
		return this.builder.password;
	}
}
