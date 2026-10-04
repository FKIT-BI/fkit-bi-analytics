# Shared database contract

Analytics owns all Flyway migrations. Generator may append raw telemetry only according to the versioned contract added here with each relevant migration. The bootstrap contains a schema marker, deliberately not a silent domain schema.
