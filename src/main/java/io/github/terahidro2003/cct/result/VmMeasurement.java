package io.github.terahidro2003.cct.result;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class VmMeasurement implements Serializable {
    
   private static final long serialVersionUID = -5204256744692818564L;
   
   List<Double> measurements = new ArrayList<Double>();
   int vm;

   public VmMeasurement(List<Double> measurements, int vm) {
      this.measurements = measurements;
      this.vm = vm;
   }

   public int getVm() {
        return vm;
    }

    public void setVm(int vm) {
        this.vm = vm;
    }

    public List<Double> getMeasurements() {
        return measurements;
    }

    public void setMeasurements(List<Double> measurements) {
        this.measurements = measurements;
    }

    public void addMeasurement(double value) {
        measurements.add(value);
    }
}
