package steam.boiler.core;

import steam.boiler.simulator.SimulationCharacteristicsDialog;
import steam.boiler.util.SteamBoilerCharacteristics;

/**
 * Provides a simple way to fire up the simulation interface using a given
 * controller.
 *
 * @author David J. Pearce
 *
 */
public class Simulation {
  /**
 * Main method for the simulation of the steam boiler.
 *
 * @param args for the main method.
 */
  public static void main(String[] args) {
    // Begin the simulation by opening the characteristics selection dialog.
    SimulationCharacteristicsDialog c = new SimulationCharacteristicsDialog((
        SteamBoilerCharacteristics cs) -> {
      return new MySteamBoilerController(cs);
    });
    System.out.print(c);
  }
}
