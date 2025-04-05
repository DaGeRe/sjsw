package io.github.terahidro2003.measurement.data;

import java.util.UUID;

public class MeasurementIdentifier {
    private UUID uuid;

    public MeasurementIdentifier() {
        this.uuid = UUID.randomUUID();
    }
    
    public UUID getUuid() {
      return uuid;
   }
}
