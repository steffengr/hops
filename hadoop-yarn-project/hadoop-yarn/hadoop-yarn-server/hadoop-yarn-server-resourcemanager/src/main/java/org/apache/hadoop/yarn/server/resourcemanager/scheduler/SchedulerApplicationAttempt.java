/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import io.hops.ha.common.TransactionState;
import io.hops.ha.common.TransactionStateImpl;
import io.hops.metadata.yarn.entity.AppSchedulingInfo;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppLiveContainers;
import io.hops.metadata.yarn.entity.FiCaSchedulerAppNewlyAllocatedContainers;
import io.hops.metadata.yarn.entity.Resource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Stable;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationResourceUsageReport;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerReservedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeCleanContainerEvent;
import org.apache.hadoop.yarn.util.resource.Resources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an application attempt from the viewpoint of the scheduler. Each
 * running app attempt in the RM corresponds to one instance of this class.
 */
@Private
@Unstable
public class SchedulerApplicationAttempt {

  private static final Log LOG =
      LogFactory.getLog(SchedulerApplicationAttempt.class);

  protected final org.apache.hadoop.yarn.server.resourcemanager.scheduler.AppSchedulingInfo
      appSchedulingInfo;//recovered
  protected Map<ContainerId, RMContainer> liveContainers =
      new HashMap<ContainerId, RMContainer>();//recovered
  protected final Map<Priority, Map<NodeId, RMContainer>> reservedContainers =
      new HashMap<Priority, Map<NodeId, RMContainer>>();
      //TORECOVER not recovered yet (capacity)
  private final Multiset<Priority> reReservations = HashMultiset.create();
      //TORECOVER not recovered yet (capacity)
  protected final org.apache.hadoop.yarn.api.records.Resource
      currentReservation =
      org.apache.hadoop.yarn.api.records.Resource.newInstance(0, 0);//recovered
  private org.apache.hadoop.yarn.api.records.Resource resourceLimit =
      org.apache.hadoop.yarn.api.records.Resource.newInstance(0, 0);//recovered
  protected org.apache.hadoop.yarn.api.records.Resource currentConsumption =
      org.apache.hadoop.yarn.api.records.Resource.newInstance(0, 0);//recovered
  protected List<RMContainer> newlyAllocatedContainers =
      new ArrayList<RMContainer>();//recovered
  /**
   * Count how many times the application has been given an opportunity to
   * schedule a task at each priority. Each time the scheduler asks the
   * application for a task at this priority, it is incremented, and each time
   * the application successfully schedules a task, it is reset to 0.
   */
  Multiset<Priority> schedulingOpportunities = HashMultiset.create();
      //TORECOVER not recovered yet (capacity)
  // Time of the last container scheduled at the current allowed level
  protected Map<Priority, Long> lastScheduledContainer =
      new HashMap<Priority, Long>();
      //TORECOVER not recovered yet (capacity, fair)
  protected Queue queue;//recovered
  protected boolean isStopped = false;//recovered
  protected final RMContext rmContext;//recovered

  public SchedulerApplicationAttempt(ApplicationAttemptId applicationAttemptId,
      String user, Queue queue, ActiveUsersManager activeUsersManager,
      RMContext rmContext) {
    this.rmContext = rmContext;
    this.appSchedulingInfo =
        new org.apache.hadoop.yarn.server.resourcemanager.scheduler.AppSchedulingInfo(
            applicationAttemptId, user, queue, activeUsersManager);
    this.queue = queue;
  }

  /**
   * Get the live containers of the application.
   *
   * @return live containers of the application
   */
  public synchronized Collection<RMContainer> getLiveContainers() {
    return new ArrayList<RMContainer>(liveContainers.values());
  }

  /**
   * Is this application pending?
   *
   * @return true if it is else false.
   */
  public boolean isPending() {
    return appSchedulingInfo.isPending();
  }

  /**
   * Get {@link ApplicationAttemptId} of the application master.
   *
   * @return <code>ApplicationAttemptId</code> of the application master
   */
  public ApplicationAttemptId getApplicationAttemptId() {
    return appSchedulingInfo.getApplicationAttemptId();
  }

  public ApplicationId getApplicationId() {
    return appSchedulingInfo.getApplicationId();
  }

  public String getUser() {
    return appSchedulingInfo.getUser();
  }

  public Map<String, ResourceRequest> getResourceRequests(Priority priority) {
    return appSchedulingInfo.getResourceRequests(priority);
  }

  public int getNewContainerId(TransactionState ts) {
    if (ts != null) {
      ((TransactionStateImpl) ts).getSchedulerApplicationInfo().
          getFiCaSchedulerAppInfo(this.getApplicationAttemptId()).
          updateAppInfo(this);
    }
    return appSchedulingInfo.getNewContainerId();
  }

  public int getLastContainerId() {
    return appSchedulingInfo.getLastContainerId();
  }

  public Collection<Priority> getPriorities() {
    return appSchedulingInfo.getPriorities();
  }

  public synchronized ResourceRequest getResourceRequest(Priority priority,
      String resourceName) {
    return this.appSchedulingInfo.getResourceRequest(priority, resourceName);
  }

  public synchronized int getTotalRequiredResources(Priority priority) {
    return getResourceRequest(priority, ResourceRequest.ANY).getNumContainers();
  }

  public synchronized org.apache.hadoop.yarn.api.records.Resource getResource(
      Priority priority) {
    return appSchedulingInfo.getResource(priority);
  }

  public String getQueueName() {
    return appSchedulingInfo.getQueueName();
  }

  public synchronized RMContainer getRMContainer(ContainerId id) {
    return liveContainers.get(id);
  }

  protected synchronized void resetReReservations(Priority priority) {
    reReservations.setCount(priority, 0);
  }

  protected synchronized void addReReservation(Priority priority) {
    reReservations.add(priority);
  }

  public synchronized int getReReservations(Priority priority) {
    return reReservations.count(priority);
  }

  /**
   * Get total current reservations. Used only by unit tests
   *
   * @return total current reservations
   */
  @Stable
  @Private
  public synchronized org.apache.hadoop.yarn.api.records.Resource getCurrentReservation() {
    return currentReservation;
  }

  public Queue getQueue() {
    return queue;
  }

  public synchronized void updateResourceRequests(
      List<ResourceRequest> requests, TransactionState ts) {
    if (!isStopped) {
      appSchedulingInfo.updateResourceRequests(requests, ts);
    }
  }

  public synchronized void stop(RMAppAttemptState rmAppAttemptFinalState,
      TransactionState ts) {
    // Cleanup all scheduling information
    isStopped = true;
    if (ts != null) {
      ((TransactionStateImpl) ts).getSchedulerApplicationInfo().
          getFiCaSchedulerAppInfo(this.getApplicationAttemptId())
          .updateAppInfo(this);
    }
    appSchedulingInfo.stop(rmAppAttemptFinalState, ts);
  }

  public synchronized boolean isStopped() {
    return isStopped;
  }


  public synchronized org.apache.hadoop.yarn.server.resourcemanager.scheduler.AppSchedulingInfo getAppSchedulingInfo() {
    return appSchedulingInfo;
  }


  /**
   * Get the list of reserved containers
   *
   * @return All of the reserved containers.
   */
  public synchronized List<RMContainer> getReservedContainers() {
    List<RMContainer> reservedContainers = new ArrayList<RMContainer>();
    for (Map.Entry<Priority, Map<NodeId, RMContainer>> e : this.reservedContainers
        .entrySet()) {
      reservedContainers.addAll(e.getValue().values());
    }
    return reservedContainers;
  }

  public synchronized RMContainer reserve(SchedulerNode node, Priority priority,
      RMContainer rmContainer, Container container,
      TransactionState transactionState) {
    // Create RMContainer if necessary
    if (rmContainer == null) {
      rmContainer = new RMContainerImpl(container, getApplicationAttemptId(),
          node.getNodeID(), appSchedulingInfo.getUser(), rmContext,
          transactionState);

      Resources.addTo(currentReservation, container.getResource());
      //HOP : Update Resources
      if (transactionState != null) {
        ((TransactionStateImpl) transactionState).getSchedulerApplicationInfo()
            .getFiCaSchedulerAppInfo(
                this.appSchedulingInfo.getApplicationAttemptId())
            .toUpdateResource(Resource.CURRENTRESERVATION, currentReservation);
      }
      // Reset the re-reservation count
      resetReReservations(priority);
    } else {
      // Note down the re-reservation
      addReReservation(priority);
    }
    rmContainer.handle(
        new RMContainerReservedEvent(container.getId(), container.getResource(),
            node.getNodeID(), priority, transactionState));

    Map<NodeId, RMContainer> reservedContainers =
        this.reservedContainers.get(priority);
    if (reservedContainers == null) {
      reservedContainers = new HashMap<NodeId, RMContainer>();
      this.reservedContainers.put(priority, reservedContainers);
    }
    reservedContainers.put(node.getNodeID(), rmContainer);

    LOG.info("Application " + getApplicationId() + " reserved container " +
        rmContainer + " on node " + node + ", currently has " +
        reservedContainers.size() + " at priority " + priority +
        "; currentReservation " + currentReservation.getMemory());

    return rmContainer;
  }

  /**
   * Has the application reserved the given <code>node</code> at the given
   * <code>priority</code>?
   *
   * @param node
   *     node to be checked
   * @param priority
   *     priority of reserved container
   * @return true is reserved, false if not
   */
  public synchronized boolean isReserved(SchedulerNode node,
      Priority priority) {
    Map<NodeId, RMContainer> reservedContainers =
        this.reservedContainers.get(priority);
    if (reservedContainers != null) {
      return reservedContainers.containsKey(node.getNodeID());
    }
    return false;
  }

  public synchronized void setHeadroom(
      org.apache.hadoop.yarn.api.records.Resource globalLimit,
      TransactionState transactionState) {
    this.resourceLimit = globalLimit;
    if (transactionState != null) {
      ((TransactionStateImpl) transactionState).getSchedulerApplicationInfo()
          .getFiCaSchedulerAppInfo(
              this.appSchedulingInfo.getApplicationAttemptId())
          .toUpdateResource(Resource.RESOURCELIMIT, resourceLimit);
    }
  }

  /**
   * Get available headroom in terms of resources for the application's user.
   *
   * @return available resource headroom
   */
  public synchronized org.apache.hadoop.yarn.api.records.Resource getHeadroom() {
    // Corner case to deal with applications being slightly over-limit
    if (resourceLimit.getMemory() < 0) {
      resourceLimit.setMemory(0);
    }

    return resourceLimit;
  }

  public synchronized int getNumReservedContainers(Priority priority) {
    Map<NodeId, RMContainer> reservedContainers =
        this.reservedContainers.get(priority);
    return (reservedContainers == null) ? 0 : reservedContainers.size();
  }

  @SuppressWarnings("unchecked")
  public synchronized void containerLaunchedOnNode(ContainerId containerId,
      NodeId nodeId, TransactionState transactionState) {
    // Inform the container
    RMContainer rmContainer = getRMContainer(containerId);
    if (rmContainer == null) {
      // Some unknown container sneaked into the system. Kill it.
      rmContext.getDispatcher().getEventHandler().handle(
          new RMNodeCleanContainerEvent(nodeId, containerId, transactionState));
      return;
    }

    rmContainer.handle(
        new RMContainerEvent(containerId, RMContainerEventType.LAUNCHED,
            transactionState));
  }

  public synchronized void showRequests() {
    if (LOG.isDebugEnabled()) {
      for (Priority priority : getPriorities()) {
        Map<String, ResourceRequest> requests = getResourceRequests(priority);
        if (requests != null) {
          LOG.debug("showRequests:" + " application=" + getApplicationId() +
              " headRoom=" + getHeadroom() + " currentConsumption=" +
              currentConsumption.getMemory());
          for (ResourceRequest request : requests.values()) {
            LOG.debug("showRequests:" + " application=" + getApplicationId() +
                " request=" + request);
          }
        }
      }
    }
  }

  public org.apache.hadoop.yarn.api.records.Resource getCurrentConsumption() {
    return currentConsumption;
  }

  public void recover(AppSchedulingInfo hopInfo, RMStateStore.RMState state) {
    this.appSchedulingInfo.recover(hopInfo, state);
    ApplicationAttemptId applicationAttemptId =
        this.appSchedulingInfo.getApplicationAttemptId();
    this.isStopped = hopInfo.isStoped();
    try {
      Resource recoveringCurrentReservation = state
          .getResource(applicationAttemptId.toString(),
              Resource.CURRENTRESERVATION,
              Resource.SCHEDULERAPPLICATIONATTEMPT);
      if (recoveringCurrentReservation != null) {
        currentReservation.setMemory(recoveringCurrentReservation.getMemory());
        currentReservation
            .setVirtualCores(recoveringCurrentReservation.getVirtualCores());
      }
      Resource recoveringResourceLimit = state
          .getResource(applicationAttemptId.toString(), Resource.RESOURCELIMIT,
              Resource.SCHEDULERAPPLICATIONATTEMPT);
      if (recoveringResourceLimit != null) {
        resourceLimit.setMemory(recoveringResourceLimit.getMemory());
        resourceLimit
            .setVirtualCores(recoveringResourceLimit.getVirtualCores());
      }
      Resource recoveringCurrentConsumption = state
          .getResource(applicationAttemptId.toString(),
              Resource.CURRENTCONSUMPTION,
              Resource.SCHEDULERAPPLICATIONATTEMPT);
      if (recoveringCurrentConsumption != null) {
        currentConsumption.setMemory(recoveringCurrentConsumption.getMemory());
        currentConsumption
            .setVirtualCores(recoveringCurrentConsumption.getVirtualCores());
      }
      recoverNewlyAllocatedContainers(applicationAttemptId, state);
      recoverLiveContainers(applicationAttemptId, state);
    } catch (IOException ex) {
      Logger.getLogger(SchedulerApplicationAttempt.class.getName())
          .log(Level.SEVERE, null, ex);
    }
  }

  private void recoverNewlyAllocatedContainers(
      ApplicationAttemptId applicationAttemptId, RMStateStore.RMState state) {
    try {
      List<FiCaSchedulerAppNewlyAllocatedContainers> list =
          state.getNewlyAllocatedContainers(applicationAttemptId.toString());
      if (list != null && !list.isEmpty()) {
        for (FiCaSchedulerAppNewlyAllocatedContainers hop : list) {

          newlyAllocatedContainers
              .add(state.getRMContainer(hop.getRmcontainer_id(), rmContext));
        }
      }
    } catch (IOException ex) {
      Logger.getLogger(SchedulerApplicationAttempt.class.getName())
          .log(Level.SEVERE, null, ex);
    }
  }

  //Nikos: maybe the recovery of liveContainers should take place in the above method.
  private void recoverLiveContainers(ApplicationAttemptId applicationAttemptId,
      RMStateStore.RMState state) {
    try {
      List<FiCaSchedulerAppLiveContainers> list =
          state.getLiveContainers(applicationAttemptId.toString());
      if (list != null) {
        for (FiCaSchedulerAppLiveContainers hop : list) {
          RMContainer rMContainer =
              state.getRMContainer(hop.getRmcontainer_id(), rmContext);
          liveContainers.put(rMContainer.getContainerId(), rMContainer);
        }
      }
    } catch (IOException ex) {
      Logger.getLogger(SchedulerApplicationAttempt.class.getName())
          .log(Level.SEVERE, null, ex);
    }
  }

  public static class ContainersAndNMTokensAllocation {

    List<Container> containerList;
    List<NMToken> nmTokenList;

    public ContainersAndNMTokensAllocation(List<Container> containerList,
        List<NMToken> nmTokenList) {
      this.containerList = containerList;
      this.nmTokenList = nmTokenList;
    }

    public List<Container> getContainerList() {
      return containerList;
    }

    public List<NMToken> getNMTokenList() {
      return nmTokenList;
    }
  }

  // Create container token and NMToken altogether, if either of them fails for
  // some reason like DNS unavailable, do not return this container and keep it
  // in the newlyAllocatedContainers waiting to be refetched.
  public synchronized ContainersAndNMTokensAllocation pullNewlyAllocatedContainersAndNMTokens(
      TransactionState transactionState) {
    List<Container> returnContainerList =
        new ArrayList<Container>(newlyAllocatedContainers.size());
    List<NMToken> nmTokens = new ArrayList<NMToken>();
    for (Iterator<RMContainer> i = newlyAllocatedContainers.iterator();
         i.hasNext(); ) {
      RMContainer rmContainer = i.next();
      Container container = rmContainer.getContainer();
      try {
        // create container token and NMToken altogether.
        container.setContainerToken(rmContext.getContainerTokenSecretManager()
            .createContainerToken(container.getId(), container.getNodeId(),
                getUser(), container.getResource()));
        NMToken nmToken = rmContext.getNMTokenSecretManager()
            .createAndGetNMToken(getUser(), getApplicationAttemptId(),
                container);
        if (nmToken != null) {
          nmTokens.add(nmToken);
        }
      } catch (IllegalArgumentException e) {
        // DNS might be down, skip returning this container.
        LOG.error("Error trying to assign container token and NM token to" +
            " an allocated container " + container.getId(), e);
        continue;
      }
      returnContainerList.add(container);
      i.remove();
      ((TransactionStateImpl) transactionState).getSchedulerApplicationInfo()
          .getFiCaSchedulerAppInfo(
              this.appSchedulingInfo.getApplicationAttemptId())
          .setNewlyAllocatedContainersToRemove(rmContainer);
      rmContainer.handle(new RMContainerEvent(rmContainer.getContainerId(),
          RMContainerEventType.ACQUIRED, transactionState));
    }
    return new ContainersAndNMTokensAllocation(returnContainerList, nmTokens);
  }

  public synchronized void updateBlacklist(List<String> blacklistAdditions,
      List<String> blacklistRemovals, TransactionState ts) {
    if (!isStopped) {
      this.appSchedulingInfo
          .updateBlacklist(blacklistAdditions, blacklistRemovals, ts);
    }
  }

  public boolean isBlacklisted(String resourceName) {
    return this.appSchedulingInfo.isBlacklisted(resourceName);
  }

  public synchronized void addSchedulingOpportunity(Priority priority) {
    schedulingOpportunities
        .setCount(priority, schedulingOpportunities.count(priority) + 1);
  }

  public synchronized void subtractSchedulingOpportunity(Priority priority) {
    int count = schedulingOpportunities.count(priority) - 1;
    this.schedulingOpportunities.setCount(priority, Math.max(count, 0));
  }

  /**
   * Return the number of times the application has been given an opportunity
   * to
   * schedule a task at the given priority since the last time it successfully
   * did so.
   */
  public synchronized int getSchedulingOpportunities(Priority priority) {
    return schedulingOpportunities.count(priority);
  }

  /**
   * Should be called when an application has successfully scheduled a
   * container, or when the scheduling locality threshold is relaxed. Reset
   * various internal counters which affect delay scheduling
   *
   * @param priority
   *     The priority of the container scheduled.
   */
  public synchronized void resetSchedulingOpportunities(Priority priority) {
    resetSchedulingOpportunities(priority, System.currentTimeMillis());
  }
  // used for continuous scheduling

  public synchronized void resetSchedulingOpportunities(Priority priority,
      long currentTimeMs) {
    lastScheduledContainer.put(priority, currentTimeMs);
    schedulingOpportunities.setCount(priority, 0);
  }

  public synchronized ApplicationResourceUsageReport getResourceUsageReport() {
    return ApplicationResourceUsageReport
        .newInstance(liveContainers.size(), reservedContainers.size(),
            Resources.clone(currentConsumption),
            Resources.clone(currentReservation),
            Resources.add(currentConsumption, currentReservation));
  }

  public synchronized Map<ContainerId, RMContainer> getLiveContainersMap() {
    return this.liveContainers;
  }

  public synchronized org.apache.hadoop.yarn.api.records.Resource getResourceLimit() {
    return this.resourceLimit;
  }

  //For testing
  public synchronized List<RMContainer> getNewlyAllocatedContainers() {
    return this.newlyAllocatedContainers;
  }

  public synchronized Map<Priority, Long> getLastScheduledContainer() {
    return this.lastScheduledContainer;
  }

  public synchronized void transferStateFromPreviousAttempt(
      SchedulerApplicationAttempt appAttempt) {
    LOG.debug("transfering state for appattempt " +
        this.appSchedulingInfo.getApplicationAttemptId());
    this.liveContainers = appAttempt.getLiveContainersMap();
    this.currentConsumption = appAttempt.getCurrentConsumption();
    this.resourceLimit = appAttempt.getResourceLimit();
    this.lastScheduledContainer = appAttempt.getLastScheduledContainer();
    this.appSchedulingInfo.transferStateFromPreviousAppSchedulingInfo(
        appAttempt.appSchedulingInfo);
  }

  //TORECOVER
  public synchronized void move(Queue newQueue) {
    QueueMetrics oldMetrics = queue.getMetrics();
    QueueMetrics newMetrics = newQueue.getMetrics();
    String user = getUser();
    for (RMContainer liveContainer : liveContainers.values()) {
      org.apache.hadoop.yarn.api.records.Resource resource =
          liveContainer.getContainer().getResource();
      oldMetrics.releaseResources(user, 1, resource);
      newMetrics.allocateResources(user, 1, resource, false);
    }
    for (Map<NodeId, RMContainer> map : reservedContainers.values()) {
      for (RMContainer reservedContainer : map.values()) {
        org.apache.hadoop.yarn.api.records.Resource resource =
            reservedContainer.getReservedResource();
        oldMetrics.unreserveResource(user, resource);
        newMetrics.reserveResource(user, resource);
      }
    }

    appSchedulingInfo.move(newQueue);
    this.queue = newQueue;
  }
}
