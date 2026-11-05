package io.openems.edge.meter.acrel;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.types.MeterType;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String modbusId;
		private String inputs = "1A,1B,1C";
		private MeterType type = MeterType.GRID;
		private int ctTurns = 1;
		private boolean invert;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setModbusId(String modbusId) {
			this.modbusId = modbusId;
			return this;
		}

		public Builder setInputs(String inputs) {
			this.inputs = inputs;
			return this;
		}

		public Builder setType(MeterType type) {
			this.type = type;
			return this;
		}

		public Builder setCtTurns(int ctTurns) {
			this.ctTurns = ctTurns;
			return this;
		}

		public Builder setInvert(boolean invert) {
			this.invert = invert;
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
	public String inputs() {
		return this.builder.inputs;
	}

	@Override
	public MeterType type() {
		return this.builder.type;
	}

	@Override
	public int ctTurns() {
		return this.builder.ctTurns;
	}

	@Override
	public boolean invert() {
		return this.builder.invert;
	}

	@Override
	public String modbus_id() {
		return this.builder.modbusId;
	}

	@Override
	public int modbusUnitId() {
		return 1;
	}

}
