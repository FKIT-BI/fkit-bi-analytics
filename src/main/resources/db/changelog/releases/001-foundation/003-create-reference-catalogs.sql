--liquibase formatted sql

--changeset fkit-bi:001-003-create-signal-semantic-catalog
CREATE TABLE equipment.signal_semantic (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO equipment.signal_semantic (code, name, description, status) VALUES
    ('TEMPERATURE', 'Temperature', 'Temperature measurement', 'ACTIVE'),
    ('PRESSURE', 'Pressure', 'Pressure measurement', 'ACTIVE'),
    ('FLOW_RATE', 'Flow rate', 'Volumetric or mass flow rate', 'ACTIVE'),
    ('LEVEL', 'Level', 'Material or liquid level', 'ACTIVE'),
    ('HUMIDITY', 'Humidity', 'Relative or absolute humidity', 'ACTIVE'),
    ('ROTATIONAL_SPEED', 'Rotational speed', 'Rotational speed', 'ACTIVE'),
    ('LINEAR_SPEED', 'Linear speed', 'Linear speed', 'ACTIVE'),
    ('VIBRATION', 'Vibration', 'Vibration measurement', 'ACTIVE'),
    ('VOLTAGE', 'Voltage', 'Electrical voltage', 'ACTIVE'),
    ('CURRENT', 'Current', 'Electrical current', 'ACTIVE'),
    ('POWER', 'Power', 'Electrical or mechanical power', 'ACTIVE'),
    ('ENERGY', 'Energy', 'Accumulated energy', 'ACTIVE'),
    ('TORQUE', 'Torque', 'Rotational force', 'ACTIVE'),
    ('FORCE', 'Force', 'Applied force', 'ACTIVE'),
    ('POSITION', 'Position', 'Position measurement', 'ACTIVE'),
    ('DISTANCE', 'Distance', 'Distance measurement', 'ACTIVE'),
    ('PRODUCTION_COUNT', 'Production count', 'Count of produced items', 'ACTIVE'),
    ('SCRAP_COUNT', 'Scrap count', 'Count of scrapped items', 'ACTIVE'),
    ('CYCLE_COUNT', 'Cycle count', 'Count of completed cycles', 'ACTIVE'),
    ('OPERATING_FLAG', 'Operating flag', 'Whether the machine is operating', 'ACTIVE'),
    ('READY_FLAG', 'Ready flag', 'Whether the machine is ready', 'ACTIVE'),
    ('ALARM_FLAG', 'Alarm flag', 'Whether an alarm is active', 'ACTIVE'),
    ('MACHINE_MODE', 'Machine mode', 'Current operating mode', 'ACTIVE'),
    ('QUALITY_CODE', 'Quality code', 'Categorical quality state', 'ACTIVE'),
    ('LOAD_PERCENT', 'Load percent', 'Load as a percentage', 'ACTIVE'),
    ('CUSTOM', 'Custom', 'Application-defined signal semantic', 'ACTIVE');

--changeset fkit-bi:001-004-create-measurement-unit-catalog
CREATE TABLE equipment.measurement_unit (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    symbol VARCHAR(32),
    quantity_code VARCHAR(64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO equipment.measurement_unit (code, name, symbol, quantity_code, status) VALUES
    ('PCS', 'Piece', 'pcs', 'COUNT', 'ACTIVE'),
    ('METER', 'Meter', 'm', 'LENGTH', 'ACTIVE'),
    ('KILOGRAM', 'Kilogram', 'kg', 'MASS', 'ACTIVE'),
    ('LITER', 'Liter', 'L', 'VOLUME', 'ACTIVE'),
    ('CELSIUS', 'Degree Celsius', '°C', 'TEMPERATURE', 'ACTIVE'),
    ('PERCENT', 'Percent', '%', 'RATIO', 'ACTIVE'),
    ('RPM', 'Revolutions per minute', 'rpm', 'ROTATIONAL_SPEED', 'ACTIVE'),
    ('BAR', 'Bar', 'bar', 'PRESSURE', 'ACTIVE');
