package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.SteamBoilerCharacteristics;


/**
 * Controller class for the Steam Boiler.
 *
 *@author paula
 *
 */
public class MySteamBoilerController implements SteamBoilerController {

  /**
 * Initializes pump1 to false (closed).
 */
  boolean pump1 = false;
  /**
 * Initializes pump2 to false (closed).
 */
  boolean pump2 = false;
  /**
 * Initializes pump3 to false (closed).
 */
  boolean pump3 = false;
  /**
 * Initializes pump4 to false (closed).
 */
  boolean pump4 = false;
  /**
 * Initializes pump5 to false (closed).
 */
  boolean pump5 = false;
  /**
 * Initializes pump6 to false (closed).
 */
  boolean pump6 = false;
  /**
 * Initializes pumpIsOpen to false.
 */
  boolean pumpIsOpen = false;
  /**
 * Initializes a steam failure going into degraded to false.
 */
  boolean degradedSteam = false;
  /**
 * Initializes steam error stuck at -1 to false.
 */
  boolean steam1Error = false;
  
  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */
  private enum State {
        /**
         * Mode in which the program waits for the message STEAM_BOILER_WAITING
         * to come from the physical units.
         * Program checks whether the quantity of steam coming out of the steam boiler
         * is really zero.
         */
        WAITING, 
        
        /**
         * Mode in which the program goes to when the level of the water in the steam
         * boiler is satisfactory (between its two limit values).
         */
        READY, 
        
        /**
         * Standard operating mode in which the program tries to maintain a safe water level
         * while all physical units operate correctly.
         */
        NORMAL, 
        
        /**
         * Mode in which the program tries to maintain a satisfactory water level
         * despite of the presence of failure of some physical unit.
         * Assumed that the water level measuring unit is working correctly.
         */
        DEGRADED,
        
        /**
         * Mode in which the program tries to maintain a satisfactory water level
         * despite of the failure of the water level measuring unit.
         */
        RESCUE,
        
        /**
         * Mode in which the program has to go when either the
         * vital units have a failure or when the water value risks 
         * to reach one of its two limit values.
         */
        EMERGENCY_STOP
  }

  /**
   * Records the configuration characteristics for the given boiler problem.
   */
  private final @Nullable SteamBoilerCharacteristics configuration;

  /**
   * Identifies the current mode in which the controller is operating.
   */
  private State mode = State.WAITING;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration1
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(@Nullable SteamBoilerCharacteristics configuration1) {
    this.configuration = configuration1;
  }

  /**
 * This message is displayed in the simulation window, and enables a limited
 * form of debug output. The content of the message has no material effect on
 * the system, and can be whatever is desired. In principle, however, it should
 * display a useful message indicating the current state of the controller.
 *
 * @return the current mode in which the controller is operating.
 */
  @Override
  public @Nullable String getStatusMessage() {
    return this.mode.toString();
  }

  /**
 * Process a clock signal which occurs every 5 seconds. This requires reading
 * the set of incoming messages from the physical units and producing a set of
 * output messages which are sent back to them.
 *
 * @param incoming The set of incoming messages from the physical units.
 * @param outgoing Messages generated during the execution of this method should
 *                 be written here.S
 */
  @Override
 public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
    // Extract expected messages
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, 
       incoming);
    //
    if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, 
                    pumpControlStateMessages)) {
      // Level and steam messages required, so emergency stop.
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      this.mode = State.EMERGENCY_STOP;
    }
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    if (this.mode == State.WAITING) {
      initialisation(incoming, outgoing);
    } else if (this.mode == State.NORMAL) {
      normal(incoming, outgoing);
    } else if (this.mode == State.DEGRADED) {
      degraded(incoming, outgoing);
    } else if (this.mode == State.RESCUE) {
      rescue(incoming, outgoing);
    }
  }
  
  /**
 * Method that initializes the steam boiler. Checks if the steam level is zero
 * and if not goes into emergency stop.
 * Opens a pump when the water level is below N1.
 * Activates the valve when the water is above N2.
 * Send the signal "PROGRAM_READY" when the water level is between N1 and N2.
 * When the PHYSICAL_UNITS_READY has been received the program enters normal mode
 * or the mode degraded if any physical unit is defective.
 * Transmission failure goes into emergency stop.
 *
 * @param incoming The set of incoming messages from the physical units.
 * @param outgoing Messages generated during the execution of this method
 */
  public void initialisation(Mailbox incoming, Mailbox outgoing) {
    // Extract expected messages
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    checkFailuresInitialization(incoming, outgoing);
    assert steamMessage != null;
    assert levelMessage != null;
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
    if (extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming) != null) {
      double steamValue = steamMessage.getDoubleParameter();
      //Checks if the steam level is zero. And if not goes into emergency stop.
      if (steamValue != 0.00) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      } 
      if (getWaterLevel(incoming) < 0 || c.getCapacity() < getWaterLevel(incoming)) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
        outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      }
      if (getWaterLevel(incoming) > c.getMaximalNormalLevel()) {
        outgoing.send(new Message(MessageKind.VALVE));
      }
      if (getWaterLevel(incoming) < c.getMinimalNormalLevel()) {
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
        this.pump1 = true;
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
        this.pump2 = true;
      }
      if (extractOnlyMatch(MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT, incoming) != null) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      }
      if (getWaterLevel(incoming) > c.getMinimalNormalLevel() 
              &&  getWaterLevel(incoming) < c.getMaximalNormalLevel()) {
        outgoing.send(new Message(MessageKind.PROGRAM_READY));
      }
    }  
    Message physicalUnits = extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming);
    if (physicalUnits != null) {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
      this.mode = State.NORMAL;
    }
  } 
  
  /**
   * Checks if there is any failure with the steam boiler. Goes into its according mode if 
   * a failure is found for initialization.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing outgoing Messages generated during the execution of this method
   */
  public void checkFailuresInitialization(Mailbox incoming, Mailbox outgoing) {
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    assert steamMessage != null;
    if (getWaterLevel(incoming) == -1 || c.getCapacity() < getWaterLevel(incoming)) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, 
      incoming);
    if (((pumpStateMessages[0].getBooleanParameter() == true) 
            && (pumpControlStateMessages[0].getBooleanParameter() == true)) 
             && getWaterLevel(incoming) == 0) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }
    if (((pumpStateMessages[0].getBooleanParameter() == false) 
            && (pumpControlStateMessages[0].getBooleanParameter() == false)) 
             && getWaterLevel(incoming) == 100) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }
    for (int i = 0; i <= pumpStateMessages.length - 1; i++) {
      if ((pumpStateMessages[i].getBooleanParameter()) 
            != (pumpControlStateMessages[i].getBooleanParameter()))  {
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
      } 
    }
    if (steamMessage.getDoubleParameter() == -1 
                || c.getMaximualSteamRate() < steamMessage.getDoubleParameter()) {
      this.mode = State.DEGRADED;
      this.degradedSteam = true;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
    }
  }
  
  /**
 * Getter for the current water value in the steam boiler.
 *
 * @param incoming The set of incoming messages from the physical units.
 * 
 * @return the double value of the water level.
 */
  public double getWaterLevel(Mailbox incoming) {
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    assert levelMessage != null;
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return 0;
    }
    double waterValue = levelMessage.getDoubleParameter();
    return waterValue;
  }
  

  
  /**
   * Method that goes into the standard operating mode. 
   * Program tries to maintain the water level between N1 and N2 with all 
   * physical units operating correctly.
   * Program adjusts if the water level is above N2 or bellow N1.
   * If a water level measuring failure is detected program goes into rescue mode.
   * Failure of any other physical units goes into degraded mode and a transmission
   * failure goes into emergency stop.
   * 
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method
   */
  public void normal(Mailbox incoming, Mailbox outgoing) {
    // Extract expected messages
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert steamMessage != null;
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
    assert levelMessage != null;
    if (getMin(incoming, outgoing) <= c.getMinimalLimitLevel() 
          ||  getMax(incoming, outgoing) >= c.getMaximalLimitLevel()) {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      this.mode = State.EMERGENCY_STOP;
    }
    openPumps(incoming, outgoing);
    checkFailures(incoming, outgoing);  
  }
  
  /**
 * Method that opens and closes a specific amount of pumps to maintain the water in a normal level.
 *
 * @param incoming The set of incoming messages from the physical units.
 * @param outgoing messages being called on.
 */
  public void openPumps(Mailbox incoming, Mailbox outgoing) {
    this.pump1 = false;
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    double midpoint = ((c.getMaximalNormalLevel() + c.getMinimalNormalLevel()) / 2);
    double upperMid = ((midpoint + c.getMaximalNormalLevel()) / 2);
    double lowerMid = ((midpoint + c.getMinimalNormalLevel()) / 2);
   
    if (getMax(incoming, outgoing) <= c.getMaximalNormalLevel() 
        && getMax(incoming, outgoing) >= upperMid) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 2));
      this.pump3 = false;
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
      this.pump2 = false;
    }
    
    if (getMax(incoming, outgoing) >= midpoint && getMax(incoming, outgoing) <= upperMid) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 2));
      this.pump3 = false;
    }
   
    if (this.pump1 == false) {
      if (getMin(incoming, outgoing) <= midpoint && getMin(incoming, outgoing) >= lowerMid) {
        this.pump1 = true;
        this.pumpIsOpen = true;
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
      }
    }
    
    if (getMin(incoming, outgoing) >= c.getMinimalNormalLevel() 
        && getMin(incoming, outgoing) <= lowerMid) {
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 2));
      this.pump3 = true;
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
      this.pump2 = true;
    }
    if (getMax(incoming, outgoing) >= c.getMaximalNormalLevel()) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
      this.pump1 = false;
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
      this.pump2 = false;
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 2));
      this.pump3 = false;
    }
      
    if (getMin(incoming, outgoing) <= c.getMinimalNormalLevel()) {
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
      this.pump1 = true;
      this.pumpIsOpen = false;
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
      this.pump2 = true;
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 2));
      this.pump3 = true;
    }
  }
  
  /**
 * Checks if there is any failure with the steam boiler. Goes into its according mode if 
 * a failure is found.
 *
 * @param incoming The set of incoming messages from the physical units.
 * @param outgoing outgoing Messages generated during the execution of this method
 */
  public void checkFailures(Mailbox incoming, Mailbox outgoing) {
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, 
       incoming);
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    if (this.pump1 == true && (pumpStateMessages[0].getBooleanParameter() == false) 
        && this.pumpIsOpen == false && this.degradedSteam == false) { 
      this.mode = State.DEGRADED;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, 0));
    }
    for (int i = 0; i <= pumpStateMessages.length - 1; i++) {
      if ((pumpStateMessages[i].getBooleanParameter()) 
            != (pumpControlStateMessages[i].getBooleanParameter()))  {
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      }    
    }
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert steamMessage != null;
    assert levelMessage != null;
    
    if (steamMessage.getDoubleParameter() == -1 
        || c.getMaximualSteamRate() < steamMessage.getDoubleParameter()) {
      this.mode = State.DEGRADED;
      this.degradedSteam = true;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      this.steam1Error = true;
    }
    if (steamMessage.getDoubleParameter() == 0 && this.pump2 == true) {
      this.mode = State.DEGRADED;
      this.degradedSteam = true;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
    }
    if (getWaterLevel(incoming) == -1 
         || c.getMaximalLimitLevel() < getWaterLevel(incoming)) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    }  
  }
  
  /**
   * Calculates the min estimate of the water level.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method
   * @return the min estimate.
   */
  public double getMin(Mailbox incoming, Mailbox outgoing) {
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return 0;
    }
    double pumpCap = c.getPumpCapacity(0);
    double steamRate = c.getMaximualSteamRate();
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert steamMessage != null;
    double min = getWaterLevel(incoming) + (5 * pumpCap * pumpsOpen()) - (5 * steamRate);
    return min;
      
  }
  
  /**
   * Calculates the max estimate of the water level.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method
   * @return the max estimate.
   */
  public double getMax(Mailbox incoming, Mailbox outgoing) {
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return 0;
    }
    double pumpCap = c.getPumpCapacity(0);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert steamMessage != null;
    double max = getWaterLevel(incoming) + (5 * pumpCap * pumpsOpen()) 
           -  (5 * steamMessage.getDoubleParameter());
    return max;
  }
  
  /**
 * Method that returns the int amount of pumps open.
 *
 * @return the number of pumps open.
 */
  public int pumpsOpen() {
    int pumps = 0;
    if (this.pump1 != false) {
      pumps++;
    }
    if (this.pump2 != false) {
      pumps++;
    }
    if (this.pump3 != false) {
      pumps++;
    }
    if (this.pump4 != false) {
      pumps++;
    }
    if (this.pump5 != false) {
      pumps++;
    }
    if (this.pump6 != false) {
      pumps++;
    }
    return pumps;
  }
  
  /**
   * Mode in which the program tries to maintain a satisfactory water level despite
   * presence of physical units failing.
   * Assumed water level measuring unit is working correctly.
   * Once all the units which were defective have been repaired it goes into normal mode.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method
   */
  public void degraded(Mailbox incoming, Mailbox outgoing) {
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, 
       incoming);
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    openPumps(incoming, outgoing);
    assert steamMessage != null;
    if ((extractOnlyMatch(MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n, incoming) != null)) {
      for (int i = 0; i <= pumpStateMessages.length - 1; i++) {
        if ((pumpStateMessages[i].getBooleanParameter()) 
                == (pumpControlStateMessages[i].getBooleanParameter()))  {
          this.mode = State.NORMAL;
          outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
          outgoing.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_n, i));
        } 
      }
    }
    if ((extractOnlyMatch(MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, incoming) != null)) {
      if ((pumpStateMessages[0].getBooleanParameter()) 
              == this.pump1)  {
        this.mode = State.NORMAL;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        outgoing.send(new Message(MessageKind.PUMP_REPAIRED_n, 0));
      } 
    }
    if ((extractOnlyMatch(MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT, incoming) != null)) {
      if (this.steam1Error == true && (steamMessage.getDoubleParameter() != -1 
              || c.getMaximualSteamRate() > steamMessage.getDoubleParameter())) {
        this.mode = State.NORMAL;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        outgoing.send(new Message(MessageKind.STEAM_REPAIRED));
      }
    }
    if ((extractOnlyMatch(MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT, incoming) != null)) {
      if (this.steam1Error == false && (steamMessage.getDoubleParameter() > 0)) {
        this.mode = State.NORMAL;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        outgoing.send(new Message(MessageKind.STEAM_REPAIRED));
      }
    }
    if (getWaterLevel(incoming) == -1 
            || c.getMaximalLimitLevel() < getWaterLevel(incoming)) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    } 
    if (getMin(incoming, outgoing) <= c.getMinimalLimitLevel() 
            ||  getMax(incoming, outgoing) >= c.getMaximalLimitLevel()) {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      this.mode = State.EMERGENCY_STOP;
    } 
  }
  
  /**
   * Mode in which the program tries to maintain a satisfactory water level despite
   * of the failure of the water measuring unit.
   * As soon as the water measuring unit is repaired, the program returns to the mode
   * degraded, or normal.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method
   */
  public void rescue(Mailbox incoming, Mailbox outgoing) {
    SteamBoilerCharacteristics c = this.configuration;
    if (c == null) {
      return;
    }       
  }
  
  /**
 * Check whether there was a transmission failure. This is indicated in several
 * ways. Firstly, when one of the required messages is missing. Secondly, when
 * the values returned in the messages are nonsensical.
 *
 * @param levelMessage      Extracted LEVEL_v message.
 * @param steamMessage      Extracted STEAM_v message.
 * @param pumpStates        Extracted PUMP_STATE_n_b messages.
 * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
 * @return false for when a failure was detected and true for when no failure occurred.
 */
  private boolean transmissionFailure(@Nullable Message levelMessage, 
      @Nullable Message steamMessage, 
      Message[] pumpStates, Message[] pumpControlStates) {
    SteamBoilerCharacteristics c = this.configuration;
    if (c != null) {
      // Check level readings
      if (levelMessage == null) {
        // Nonsense or missing level reading
        return true;
      } else if (steamMessage == null) {
        // Nonsense or missing steam reading
        return true;
      } else if (pumpStates.length != c.getNumberOfPumps()) {
        // Nonsense pump state readings
        return true;
      } else if (pumpControlStates.length != c.getNumberOfPumps()) {
        // Nonsense pump control state readings
        return true;
      }
    }
    // Done
    return false;

  }

  /**
  * Find and extract a message of a given kind in a mailbox. This must the only
  * match in the mailbox, else <code>null</code> is returned.
  *
  * @param kind     The kind of message to look for.
  * @param incoming The mailbox to search through.
  * @return The matching message, or <code>null</code> if there was not exactly
  *         one match.
  */
  private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
    Message match = null;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        if (match == null) {
          match = ith;
        } else {
          // This indicates that we matched more than one message of the given kind.
          return null;
        }
      }
    }
    return match;
  }

  /**
     * Find and extract all messages of a given kind.
     *
     * @param kind     The kind of message to look for.
     * @param incoming The mailbox to search through.
     * @return The array of matches, which can empty if there were none.
     */
  private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
    int count = 0;
    // Count the number of matches
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        count = count + 1;
      }
    }
    // Now, construct resulting array
    Message[] matches = new Message[count];
    int index = 0;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        matches[index++] = ith;
      }
    }
    return matches;
  }
}
