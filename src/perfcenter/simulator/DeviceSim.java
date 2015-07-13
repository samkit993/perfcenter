/*
 * Copyright (C) 2011-12  by Varsha Apte - <varsha@cse.iitb.ac.in>, et al.
 * This file is distributed as part of PerfCenter
 *
 *  This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */
/**
 * Inherits from Device. Has some more functions for Simulations
 * @author  akhila
 */
package perfcenter.simulator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import org.apache.log4j.Logger;

import perfcenter.simulator.metric.DiscreteSampleAverageMetric;
import perfcenter.baseclass.Device;
import perfcenter.baseclass.ModelParameters;
import perfcenter.baseclass.enums.PowerManagementGovernor;
//import simulator.metric.DiscreteSampleAverageMetric;
import perfcenter.simulator.queue.QServerInstance;
import perfcenter.simulator.queue.QueueServer;
import perfcenter.simulator.queue.QueueSim;
import perfcenter.simulator.request.Request;

/**
 * Contains simulation structures and simulation logic for Device. 
 *
 */
public class DeviceSim extends Device implements QueueServer {

	Logger logger = Logger.getLogger("DeviceSim");

	public DiscreteSampleAverageMetric averageFrequencySim = new DiscreteSampleAverageMetric(0.95);// FIXME remove hardcoding
	
	public DeviceSim(Device d) {
		// make a copy of device from SimParams.ds
		// this copy will be in SimParams.res
		name = d.name;
		count = d.count;
		buffer = d.buffer;
		schedulingPolicy = d.schedulingPolicy;
		speedUpFactor = d.speedUpFactor;

		// Copy these attributes also for new device copy form SimParamms.ds to SimParams.res: rakesh
		governor = d.governor;
		deviceProbeInterval = d.deviceProbeInterval;
		availabelSpeedLevels = d.availabelSpeedLevels;
		powerConsumptionsLevels = d.powerConsumptionsLevels;
		upThreshold = d.upThreshold;
		downThreshold = d.downThreshold;
		isDevicePowerManaged = d.isDevicePowerManaged;
		userspaceSpeedIndex = d.userspaceSpeedIndex;
		idlePower = d.idlePower;
		totalFrequencyLevels = d.totalFrequencyLevels;
		totalPowerLevels = d.totalPowerLevels;
		int i = 0;
		while (this.availabelSpeedLevels[i + 1] > this.availabelSpeedLevels[i]) {
			i++;
		}
		totalFrequencyLevels = i;

		// Dynamically load the scheduling policy class based on policy name given
		// in input file
		resourceQueue = QueueSim.loadSchedulingPolicyClass(schedulingPolicy.toString(), (int) buffer.getValue(), (int) count.getValue(),/* "hwRes", */
				this);
		resourceQueue.initialize();
	}

	public DeviceSim(String name) {
		super(name);
	}

	public void print() {
		super.print();
	}

	// this generates hardware task starts event
	public void createStartTaskEvent(Request req, int idleInstanceId, double currTime) {
		// set the request device Instance
		req.devInstance = idleInstanceId;
		SimulationParameters.offerEvent(new Event(currTime, EventType.HARDWARE_TASK_STARTS, req));

		// resourceQueue.request = req;
		resourceQueue.serverName = req.fromServer;
		resourceQueue.hostName = req.hostObject.getName();
		resourceQueue.deviceName = this.name;
	}

	@Override
	public void enqueue(Request req, double time) throws Exception {
//		((QueueSim) resourceQueue).enqueue_Device(req, time);
		((QueueSim) resourceQueue).enqueue(req, time);
	}

	@Override
	public void dequeue() {
	}

	public void endService(Request request, int instanceId, double time) throws Exception {
		// ((QueueSim) deviceQ).endDeviceService(instanceId, time);
		((QueueSim) resourceQueue).endService(request, instanceId, time);
	}

	void recordCISampleAtTheEndOfSimulation() {
		((QueueSim) resourceQueue).recordCISampleAtTheEndOfSimulation();
		for (int slot = 0; slot < ModelParameters.intervalSlotCount; slot++) {
			averageFrequencySim.recordCISample(slot);
		}
		/*
		 * ArrayList<QServerInstance>qServerInstances = ((QueueSim) resourceQueue).qServerInstances; double serverBusyTime=0; for (QServerInstance qsi
		 * : qServerInstances) { for (SoftServer srv : softServers) { serverBusyTime=serverBusyTime+qsi.perSoftServerBusyTimeSim.getTotalValue(); }
		 */
	}

	/**
	 * If there is next hardware device, then request is offered to the device. else new event software task end is generated.
	 * 
	 * When a task at device ends, then following are the five options.
	 * 
	 * <pre>
	 * if there is next device
	 * 		1. offer request to next device.
	 * 		return
	 * else (there is no next device)
	 * 		if device was part of task
	 * 			if there is next virt res
	 * 				2. offer request to next virt res
	 * 				return
	 * 			else (there is no next virt res)
	 * 				3. new event software task end is generated
	 * 				return
	 * 		else if device was part of soft res
	 * 			if there is next level softres
	 * 				4. offer request to it
	 * 				return
	 * 			else
	 * 				5. new event virtual res task ends is generated
	 * 				return
	 * </pre>
	 */
	public void processTaskEndEvent(Request request, int instanceId, double currTime) throws Exception {
		try {
			QServerInstance qsi = ((QueueSim) resourceQueue).qServerInstances.get(instanceId);
			qsi.deviceInstanceAssociatedEventList.remove(SimulationParameters.currentEventBeingHandled);

			updateDeviceSpeedup(qsi, currTime);
			endService(request, instanceId, SimulationParameters.currentTime);

			// advance the pointer to check whether there is next device for the task
			request.setDeviceIndex(request.getDeviceIndex() + 1, "DeviceSim:processTaskEndEvent");
			// Option 1
			// if there is no more devices then nextDeviceFound will be -1
			boolean nextDeviceFound = request.hostObject.offeredRequestToNextDevice(request, SimulationParameters.currentTime);
			if (nextDeviceFound == true) {
				return;
			}

			// as there are no more devices request could be offered to virtual res
			if (request.isRequestFromTask()) {
				// Option 2
				request.virtualResIndex = 0;
				boolean nextSoftResFound = request.hostObject.offeredRequestToVirtualRes(request, SimulationParameters.currentTime);
				logger.debug("virtual res: " + nextSoftResFound);
				if (nextSoftResFound == true) {
					return;
				}

				// Option 3
				// As there are no more soft resources
				SimulationParameters.offerEvent(new Event(SimulationParameters.currentTime, EventType.SOFTWARE_TASK_ENDS, request));
			} else if (request.isRequestFromVirtRes()) {
				// Option 4
				boolean nextLayerSoftResFound = request.hostObject.offeredRequestToNextLayerVirtRes(request, SimulationParameters.currentTime);
				if (nextLayerSoftResFound == true) {
					return;
				}

				// Option 5
				// there is no next virtual res
				SimulationParameters.offerEvent(new Event(SimulationParameters.currentTime, EventType.VIRTUALRES_TASK_ENDS, request));
				logger.debug("NO virtual resource ");
			}
			// } catch (Exception e) {
			// throw new Exception("HExec error"); //Bhavin
		} finally {
		}
	}

	/**
	 * This generates hardware task end event
	 */
	public void processTaskStartEvent(Request request, double currentTime) throws Exception {
		// change servt based on device instance speed
		QServerInstance qserverInstance = ((QueueSim) resourceQueue).qServerInstances.get(request.devInstance);

		// get new service time for this PM device instance.
		double serviceTime = request.serviceTimeRemaining * availabelSpeedLevels[0] / availabelSpeedLevels[qserverInstance.currentSpeedLevelIndex];
		double endTime = SimulationParameters.currentTime + serviceTime;

		// updating measurement variables.
		qserverInstance.intermediate_busy_time = 0.0;
		qserverInstance.intermediate_weighted_busy_time = 0.0;
		qserverInstance.last_time_stamp = currentTime;
		logger.debug("[In h/w_task_starts]service time: " + serviceTime + "   end_time: " + endTime);

		Event event = new Event(endTime, EventType.HARDWARE_TASK_ENDS, request);
		SimulationParameters.offerEvent(event);
		qserverInstance.deviceInstanceAssociatedEventList.add(event);
	}

	/**
	 * if the resource is a hardware resource like cpu then while discarding the request, this method: 1. takes care of the thread allocated to the
	 * request 2. takes care of contributions made to performance measures of current software server
	 */
	public void dropRequest(Request request, double currentTime) throws Exception {
		if (request.isRequestFromVirtRes()) {
			VirtualResSim virtualResourceSim = request.hostObject.getVirtualRes(request.virtResName);
			// stops the execution at the virtual resource
			virtualResourceSim.abort(request.virtualResInstance, currentTime);

			// aborts any resources held at upper layers
			virtualResourceSim.dropRequest(request, currentTime);
			logger.debug("request dropped at " + virtualResourceSim.name);
		} else {
			// stops the execution at the current thread
			request.hostObject.getServer(request.softServerName).abortThread(request.threadNum, SimulationParameters.currentTime);
			request.drop();
			// logger.debug("request dropped at");
		}
	}

	/******************************************************************************/
	/**
	 * For all devices in devQ find their utilization in probeInterval, For all tasks associated with this device instance, update their service times
	 * Schedule next probe event
	 * 
	 * Added by rakesh, modified by yogesh
	 * 
	 * @param currentTime
	 * @param host
	 */
	void deviceProbeHandler(double currentTime, HostSim host) throws Exception {
		// for all device instances
		// keep the values of maximum device speed
		double maximumDeviceSpeedLevel = 0.0;
		int maximumSpeedLevelIndex = 0;
		double busyEnergy;
		for (QServerInstance qServerInstance : ((QueueSim) resourceQueue).qServerInstances) {
			
			//compute power
			busyEnergy = 0;
			double timeSinceLastPowerUpdate = currentTime - qServerInstance.lastPowerUpdate;
			if (qServerInstance.isBusyStatus()) {// server busy
				qServerInstance.busyTimeInProbeInterval += timeSinceLastPowerUpdate; // nadeesh record value
				// nadeesh computing power of current processing request
				
				//idlepower * probeint 						+ rangepower * busytime
				//idlepower * (busytime + freetime)			+ rangepower * busytime
				//idlepower * busytime 						+ rangepower * busytime = busyEnergy
				//idlePower * freetime												= freeEnergy
				busyEnergy = computeEnergy(qServerInstance, timeSinceLastPowerUpdate, qServerInstance.isBusyStatus());
				qServerInstance.totalEnergyConsumption.recordValue(qServerInstance.currentRequest, busyEnergy);
				// main accumulation of busyTimeInProbeInterval is done in QueueServerInstance
				logger.debug("Energy: busy time in probe interval: " + qServerInstance.busyTimeInProbeInterval + " current time: "
						+ SimulationParameters.currentTime);
			} else {
				/** Created dummy request namely _idle and store the power used while server is idle **/
				Request idleRequest = new Request(0, null, currentTime); // 2nd argument of Request isthe scenario name
				idleRequest.fromServer = "_idle"; // assign idle power to a _idle server
				double freeEnergy = computeEnergy(qServerInstance, timeSinceLastPowerUpdate, qServerInstance.isBusyStatus());
				qServerInstance.totalEnergyConsumption.recordValue(idleRequest, freeEnergy);
			}
			
			// Update intermediate busy-times
			updateIntermediateBusyTime(qServerInstance, currentTime);

			// Step 3: get new device speed as selected by the governor
			// governor policy decides new device speed
			if (qServerInstance.busyTimeInProbeInterval > this.deviceProbeInterval) {
				qServerInstance.busyTimeInProbeInterval = this.deviceProbeInterval; // BHAVIN: fix for utilization going at 1.0000001
			}
			qServerInstance.utilizationInProbeInterval = qServerInstance.busyTimeInProbeInterval / this.deviceProbeInterval;
			if (qServerInstance.utilizationInProbeInterval > 1) {
				qServerInstance.utilizationInProbeInterval = 1;
			}
			
			// change cpu freq and service time remaining ----
			qServerInstance.lastSpeed = qServerInstance.currentSpeed;
			double newDeviceSpeedLevel = getCurrentDeviceSpeed(qServerInstance);

			// In case of mutli core device take the device speed which is maximum of all core for calculating total energy consumption
			if (maximumDeviceSpeedLevel < newDeviceSpeedLevel) {
				maximumDeviceSpeedLevel = newDeviceSpeedLevel;
				maximumSpeedLevelIndex = qServerInstance.currentSpeedLevelIndex;
			}

		}
		for (QServerInstance qServerInstance : ((QueueSim) resourceQueue).qServerInstances) {

			// Set the speed for all device instances
			qServerInstance.currentSpeed = maximumDeviceSpeedLevel;
			qServerInstance.currentSpeedLevelIndex = maximumSpeedLevelIndex;

			// get device speedup factor. Service time of device-instance changed proportional to this factor
			qServerInstance.newDeviceSpeedFactor = qServerInstance.lastSpeed / qServerInstance.currentSpeed;

			// Step 4: update service (remaining) times for all tasks associated with this device
			updateServiceTime(qServerInstance.newDeviceSpeedFactor, qServerInstance.deviceInstanceAssociatedEventList);

			qServerInstance.lastPowerUpdate = currentTime;
			qServerInstance.busyTimeInProbeInterval = 0;
		}

		// calculate the average frequency
		averageFrequencySim.recordValue(maximumDeviceSpeedLevel);
		// Step 4: schedule nexy cpu_prob

		double nextProbTime = currentTime + this.deviceProbeInterval;
		Event event = new Event(nextProbTime, EventType.DEVICE_PROBE, host, this);
		SimulationParameters.offerEvent(event);
	}

	/**
	 * Compute power used for the currentRequest
	 * 
	 * @param qServerInstance
	 * @param timeSinceLastUpdate
	 *            -- device busyTime
	 * @return
	 */

	public double computeEnergy(QServerInstance qServerInstance, double timeSinceLastUpdate, boolean busy) {
		// power per probe interval is computed here
		// BHAVIN: Energy calculation based on model: Pow_total = K*Utilization + IdlePower (Kumar vManage is one reference)
		// Multiplying both sides by time: Energy_total = K* Utilization*time +IdlePower*time
		// = Idlepower*probeint + K* Busytime
		
		// time here is probeInterval
		double energy = 0;
		double dynamicPower = powerConsumptionsLevels[qServerInstance.currentSpeedLevelIndex];

		//idle power is multiplied by busy time as only that will be billed to current server
		//rest of the idle power when the server was really idle will be billed to "idle" server
		energy += idlePower[qServerInstance.currentSpeedLevelIndex] * timeSinceLastUpdate;
		if(busy) {
			energy += dynamicPower * timeSinceLastUpdate;
		}
		return energy;
	}

	// Current device speed is determined by the governor policy.
	private double getCurrentDeviceSpeed(QServerInstance qServerInstance) throws Exception {
		try { // [Currently implemented for cpu-freq scaling gov. only]
			if (this.governor == PowerManagementGovernor.CONSERVATIVE) {
				// if >= up_threshold then increase freq.
				if (qServerInstance.busyTimeInProbeInterval * 100.0 > this.upThreshold * deviceProbeInterval) {

					// logger.debug("available speed level: " + availabelSpeedLevels.length);
					if (qServerInstance.currentSpeedLevelIndex < availabelSpeedLevels.length - 1
							&& availabelSpeedLevels[qServerInstance.currentSpeedLevelIndex + 1] > availabelSpeedLevels[qServerInstance.currentSpeedLevelIndex]) {
						qServerInstance.currentSpeedLevelIndex++;
						qServerInstance.currentSpeed = availabelSpeedLevels[qServerInstance.currentSpeedLevelIndex];

						logger.debug(" ----Frequency up----");
						logger.debug(" current speed: " + qServerInstance.currentSpeed);
					} else {
						logger.debug(" -----max frequency-----");
					}
				} // if <= down_threshold then decrease freq.
				else if (qServerInstance.utilizationInProbeInterval * 100 < this.downThreshold) {
					if (qServerInstance.currentSpeedLevelIndex > 0) {
						qServerInstance.currentSpeedLevelIndex--;
						qServerInstance.currentSpeed = availabelSpeedLevels[qServerInstance.currentSpeedLevelIndex];
						logger.debug(" -----Frequency down------");
						logger.debug(" current speed: " + qServerInstance.currentSpeed);
					} else {
						logger.debug(" ----Minimum frequency-----");
					}
				}
				logger.debug("");
				// else no changes in currentSpeedLevel
			} else if (this.governor == PowerManagementGovernor.ONDEMAND) {
				// if >= up_threshold then increase freq.
				if (qServerInstance.utilizationInProbeInterval * 100 > this.upThreshold) // get MAX speed
				{
					int i;
					for (i = 0; qServerInstance.currentSpeedLevelIndex < availabelSpeedLevels.length - 1
							&& availabelSpeedLevels[i + 1] > availabelSpeedLevels[i]; i++)
						; // no-op
					qServerInstance.currentSpeedLevelIndex = i;
					qServerInstance.currentSpeed = availabelSpeedLevels[i];

					logger.debug(" ----Frequency up----");
					logger.debug(" current speed: " + qServerInstance.currentSpeed);
				} // if <= down_threshold then decrease freq.
				else if (qServerInstance.utilizationInProbeInterval * 100 < this.upThreshold) // step down the frequency to next minimum level
				{
					if (qServerInstance.currentSpeedLevelIndex != 0) {
						logger.debug(" ----Frequency down----");
						qServerInstance.currentSpeedLevelIndex = qServerInstance.currentSpeedLevelIndex - 1;
						qServerInstance.currentSpeed = availabelSpeedLevels[qServerInstance.currentSpeedLevelIndex];
					} else {
						qServerInstance.currentSpeedLevelIndex = 0;
						qServerInstance.currentSpeed = availabelSpeedLevels[0];
					}
					logger.debug(" current speed: " + qServerInstance.currentSpeed);
				}
				logger.debug("");
				// else no changes in currentSpeedLevel
			} else {
				// governor = USERSPACE, POWERSAVER, PERFORMANCE
				// no change to speed level
				// POWERSAVER --- uses availabelSpeedLevels[MIN_available]
				// PERFORMANCE--- uses availabelSpeedLevels[MAX_available]
				// USERSPACE --- uses availabelSpeedLevels[]

				logger.debug("Device speed unchanged, Speed_level:  " + qServerInstance.currentSpeed);
			}
			return qServerInstance.currentSpeed;
			// } catch (Exception e) {
			// throw new Exception("\n DeviceSim update device speed error", e); //Bhavin
		} finally {
		}
	}

	// one task can be associated with many device instances -- then av. effect of all devices has to be there
	public void updateServiceTime(double newDeviceSpeedFactor, List<Event> deviceInstanceAssociatedEventList) throws Exception {
		try {
			// for(Event ev: deviceAssociatedEventList)
			for (Event ev : deviceInstanceAssociatedEventList) {
				// if(SimParams.eventList.size() > 1)
				// System.out.println(" ************ DevInstance list contains more than one event ************");

				if (newDeviceSpeedFactor != 1) {
					// Step 1: remove associated events from eventList
					SimulationParameters.eventQueue.remove(ev);

					// Step 2: modify timings for events
					ev.time = SimulationParameters.currentTime + (ev.time - SimulationParameters.currentTime) * newDeviceSpeedFactor;

					// Step 3: Insert associated events into eventList
					SimulationParameters.offerEvent(ev);
				}
			}
			// } catch (Exception e) {
			// throw new Exception("DeviceSim modifying_service_time error", e); //Bhavin
		} finally {
		}
	}

	// update intermediate busy time for a device of a request.
	// This is after a request is geting serviced and some probe intervals are in the way or already passed
	// and before request is completed

	private void updateIntermediateBusyTime(QServerInstance qsi, double currTime) {
		qsi.intermediate_busy_time += currTime - qsi.last_time_stamp;
		qsi.intermediate_weighted_busy_time += (currTime - qsi.last_time_stamp) * availabelSpeedLevels[qsi.currentSpeedLevelIndex];
		qsi.last_time_stamp = currTime;
	}

	/*
	 * This is for updating device instance speed after a request is completed. On a speed v/s timeline graph speedup = (sum area_busy_time)/(sum
	 * busy_time);
	 */
	private void updateDeviceSpeedup(QServerInstance qsi, double currTime) throws Exception {
		try {
			logger.debug("==total_bust_time: " + qsi.totalBusyTime.getTotalValue() + "  req.ST: " + qsi.reqStartTime + "  this_time: " + currTime
					+ "  inter_BT: " + qsi.intermediate_busy_time + "  currTime-req.ST: " + (currTime - qsi.reqStartTime) + "  currTime-lastTS: "
					+ (currTime - qsi.last_time_stamp) + "  prev_TBT: " + qsi.totalBusyTime.getTotalValue() + "   prev. TWBT: "
					+ qsi.totalBusyTime.getTotalValue() * qsi.avgSpeedup + "  time-TWBT: "
					+ (qsi.totalBusyTime.getTotalValue() * qsi.avgSpeedup - currTime));

			//SCALABILITY: continuous computation of average speed up is bad. Use one of the metric classes here.
			qsi.avgSpeedup = (qsi.totalBusyTime.getTotalValue() * qsi.avgSpeedup + qsi.intermediate_weighted_busy_time + (currTime - qsi.last_time_stamp)
					* availabelSpeedLevels[qsi.currentSpeedLevelIndex])
					/ ((qsi.totalBusyTime.getTotalValue() + qsi.intermediate_busy_time) + (currTime - qsi.last_time_stamp));

			logger.debug("qsi: " + qsi + "  av. speedup: " + qsi.avgSpeedup);
			// } catch (Exception e) {
			// throw new Exception("DeviceSim update device speed error", e); //Bhavin
		} finally {
		}
	}

	public void clearValuesButKeepConfInts() {
		// Setting init_speed for all Queue server instances of the device: rakesh
		// these are default settings for governors and variables associated with it.
		averageFrequencySim.clearValuesButKeepConfInts();

		((QueueSim) resourceQueue).clearValuesButKeepConfInts();
		for (QServerInstance qServerInstance : ((QueueSim) resourceQueue).qServerInstances) {
			if (this.governor == PowerManagementGovernor.POWERSAVE || this.governor == PowerManagementGovernor.CONSERVATIVE
					|| this.governor == PowerManagementGovernor.ONDEMAND) {
				qServerInstance.currentSpeedLevelIndex = 0;
				qServerInstance.currentSpeed = this.availabelSpeedLevels[0];
				qServerInstance.avgSpeedup = qServerInstance.currentSpeed;
			} else if (this.governor == PowerManagementGovernor.USERSPACE) {
				qServerInstance.currentSpeedLevelIndex = (int) this.userspaceSpeedIndex;
				qServerInstance.currentSpeed = this.availabelSpeedLevels[(int) this.userspaceSpeedIndex];
				qServerInstance.avgSpeedup = qServerInstance.currentSpeed;

			} else if (this.governor == PowerManagementGovernor.PERFORMANCE) {
				int i = 0;
				while (this.availabelSpeedLevels[i + 1] > this.availabelSpeedLevels[i]) {
					i++;
				}
				qServerInstance.currentSpeedLevelIndex = i;
				qServerInstance.currentSpeed = this.availabelSpeedLevels[i];
				qServerInstance.avgSpeedup = qServerInstance.currentSpeed;
			}
		}
	}
}