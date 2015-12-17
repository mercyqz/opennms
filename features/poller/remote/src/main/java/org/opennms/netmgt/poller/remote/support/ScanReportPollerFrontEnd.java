/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.poller.remote.support;

import static org.opennms.netmgt.poller.remote.PollerBackEnd.HOST_ADDRESS_KEY;
import static org.opennms.netmgt.poller.remote.PollerBackEnd.HOST_NAME_KEY;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.poller.DistributionContext;
import org.opennms.netmgt.poller.PollStatus;
import org.opennms.netmgt.poller.remote.ConfigurationChangedListener;
import org.opennms.netmgt.poller.remote.PollService;
import org.opennms.netmgt.poller.remote.PolledService;
import org.opennms.netmgt.poller.remote.PollerBackEnd;
import org.opennms.netmgt.poller.remote.PollerConfiguration;
import org.opennms.netmgt.poller.remote.PollerFrontEnd;
import org.opennms.netmgt.poller.remote.PollerSettings;
import org.opennms.netmgt.poller.remote.ServicePollState;
import org.opennms.netmgt.poller.remote.ServicePollStateChangedEvent;
import org.opennms.netmgt.poller.remote.ServicePollStateChangedListener;
import org.opennms.netmgt.poller.remote.TimeAdjustment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * <p>DefaultPollerFrontEnd class.</p>
 *
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 * @version $Id: $
 */
public class ScanReportPollerFrontEnd implements PollerFrontEnd, InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(ScanReportPollerFrontEnd.class);

    private class Initial extends State {
        @Override
        public void initialize() {
            try {
                assertNotNull(m_backEnd, "pollerBackEnd");
                assertNotNull(m_pollService, "pollService");
                assertNotNull(m_pollerSettings, "pollerSettings");

                final String monitorId = m_pollerSettings.getMonitoringSystemId();

                if (monitorId == null) {
                    setState(new Registering());
                } else { 
                    doLoadConfig();

                    // TODO: Check return value?
                    // TODO: Add metadata values to the details
                    m_backEnd.pollerStarting(getMonitoringSystemId(), getDetails());

                    setState(new Running());
                }
            } catch (final Throwable e) {
                setState(new FatalExceptionOccurred(e));

                // rethrow the exception on initialize so we exit if we fail to initialize
                throw e;
            }
        }

        @Override
        public boolean isRegistered() { return false; }

        @Override
        public boolean isStarted() { return false; }
    }

    private class Registering extends State {
        @Override
        public boolean isRegistered() { return false; }

        @Override
        public boolean isStarted() { return false; }

        @Override
        public void register(final String location) {
            try {
                doRegister(location);
                setState(new Running());
            } catch (final Throwable e) {
                LOG.warn("Unable to register.", e);
                setState(new FatalExceptionOccurred(e));
            }
        }
    }

    private class Running extends State {
        @Override
        public boolean isRegistered() { return true; }

        @Override
        public boolean isStarted() { return true; }

        @Override
        public void pollService(final Integer polledServiceId) {
            try {
                doPollService(polledServiceId);
            } catch (Throwable e) {
                LOG.error("Unexpected exception occurred while polling service ID {}.", polledServiceId, e);
                setState(new FatalExceptionOccurred(e));
            }
        }

    }

    public static class FatalExceptionOccurred extends State {
        private final Throwable m_throwable;

        public FatalExceptionOccurred(Throwable e) {
            m_throwable = e;
        }

        public Throwable getThrowable() {
            return m_throwable;
        }

    }

    private static abstract class State {

        public boolean isRegistered() {
            return false;
        }

        public boolean isStarted() {
            return false;
        }

        public void initialize() {
            throw new IllegalStateException("Cannot initialize() from this state: " + getClass().getSimpleName());
        }

        public void pollService(final Integer serviceId) {
            throw new IllegalStateException("Cannot pollService() from this state: " + getClass().getSimpleName());
        }

        public void register(final String location) {
            throw new IllegalStateException("Cannot register() from this state:" + getClass().getSimpleName());
        }

    }

    private State m_state = new Initial();

    // injected dependencies
    private PollerBackEnd m_backEnd;

    private PollerSettings m_pollerSettings;

    private PollService m_pollService;
    
    private TimeAdjustment m_timeAdjustment;

    // listeners
    private List<PropertyChangeListener> m_propertyChangeListeners = new LinkedList<PropertyChangeListener>();

    private List<ServicePollStateChangedListener> m_servicePollStateChangedListeners = new LinkedList<ServicePollStateChangedListener>();

    private List<ConfigurationChangedListener> m_configChangeListeners = new LinkedList<ConfigurationChangedListener>();

    // current configuration
    private PollerConfiguration m_pollerConfiguration;

    /**
     * Current state of polled services. The map key is the monitored service ID.
     */
    private Map<Integer, ServicePollState> m_pollState = new LinkedHashMap<Integer, ServicePollState>();

    /** {@inheritDoc} */
    @Override
    public void addConfigurationChangedListener(ConfigurationChangedListener l) {
        m_configChangeListeners.add(0, l);
    }

    /** {@inheritDoc} */
    @Override
    public void addPropertyChangeListener(final PropertyChangeListener listener) {
        m_propertyChangeListeners.add(0, listener);
    }

    /** {@inheritDoc} */
    @Override
    public void addServicePollStateChangedListener(final ServicePollStateChangedListener listener) {
        m_servicePollStateChangedListeners.add(0, listener);
    }

    /**
     * <p>afterPropertiesSet</p>
     *
     * @throws java.lang.Exception if any.
     */
    @Override
    public void afterPropertiesSet() {
        assertNotNull(m_timeAdjustment, "timeAdjustment");
        assertNotNull(m_backEnd, "pollerBackEnd");
        assertNotNull(m_pollService, "pollService");
        assertNotNull(m_pollerSettings, "pollerSettings");

        m_state.initialize();
    }

    /**
     * <p>destroy</p>
     *
     * @throws java.lang.Exception if any.
     */
    @Override
    public void destroy() {
        // Do nothing
    }

    /**
     * <p>doPollService</p>
     *
     * @param polledServiceId a {@link java.lang.Integer} object.
     */
    private void doPollService(final Integer polledServiceId) {
        final PollStatus result = doPoll(polledServiceId);
        if (result == null)
            return;

        updateServicePollState(polledServiceId, result);

        m_backEnd.reportResult(getMonitoringSystemId(), polledServiceId, result);
    }

    /**
     * <p>doRegister</p>
     *
     * @param location a {@link java.lang.String} object.
     */
    private void doRegister(final String location) {

        String monitoringSystemId = m_backEnd.registerLocationMonitor(location);
        m_pollerSettings.setMonitoringSystemId(monitoringSystemId);

        doLoadConfig();

        // TODO: Check return value?
        // TODO: Add metadata values to the details
        m_backEnd.pollerStarting(getMonitoringSystemId(), getDetails());
    }

    /**
     * <p>getDetails</p>
     *
     * @return a {@link java.util.Map} object.
     */
    public static Map<String, String> getDetails() {
        final HashMap<String, String> details = new HashMap<String, String>();
        final Properties p = System.getProperties();

        for (final Map.Entry<Object, Object> e : p.entrySet()) {
            if (e.getKey().toString().startsWith("os.") && e.getValue() != null) {
                details.put(e.getKey().toString(), e.getValue().toString());
            }
        }

        final InetAddress us = InetAddressUtils.getLocalHostAddress();
        details.put(HOST_ADDRESS_KEY, InetAddressUtils.str(us));
        details.put(HOST_NAME_KEY, us.getHostName());

        return Collections.unmodifiableMap(details);
    }

    /**
     * <p>getMonitoringSystemId</p>
     *
     * @return a {@link java.lang.String} object.
     */
    private String getMonitoringSystemId() {
        return m_pollerSettings.getMonitoringSystemId();
    }

    /**
     * <p>getMonitorName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getMonitorName() {
        return (isRegistered() ? m_backEnd.getMonitorName(getMonitoringSystemId()) : "");
    }

    /**
     * <p>getPolledServices</p>
     *
     * @return a {@link java.util.Collection} object.
     */
    @Override
    public Collection<PolledService> getPolledServices() {
        return Arrays.asList(m_pollerConfiguration.getPolledServices());
    }

    /**
     * <p>getPollerPollState</p>
     *
     * @return a {@link java.util.List} object.
     */
    @Override
    public List<ServicePollState> getPollerPollState() {
        synchronized (m_pollState) {
            return new LinkedList<ServicePollState>(m_pollState.values());
        }
    }

    /** {@inheritDoc} */
    @Override
    public ServicePollState getServicePollState(int polledServiceId) {
        synchronized (m_pollState) {
            return m_pollState.get(polledServiceId);
        }
    }

    /**
     * <p>isRegistered</p>
     *
     * @return a boolean.
     */
    @Override
    public boolean isRegistered() {
        return m_state.isRegistered();
    }

    /**
     * <p>isStarted</p>
     *
     * @return a boolean.
     */
    @Override
    public boolean isStarted() {
        return m_state.isStarted();
    }

    /** {@inheritDoc} */
    @Override
    public void pollService(final Integer polledServiceId) {
        m_state.pollService(polledServiceId);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final String monitoringLocation) {
        m_state.register(monitoringLocation);
    }

    /** {@inheritDoc} */
    @Override
    public void removeConfigurationChangedListener(final ConfigurationChangedListener listener) {
        m_configChangeListeners.remove(listener);
    }

    /** {@inheritDoc} */
    @Override
    public void removePropertyChangeListener(final PropertyChangeListener listener) {
        m_propertyChangeListeners.remove(listener);
    }

    /** {@inheritDoc} */
    @Override
    public void removeServicePollStateChangedListener(final ServicePollStateChangedListener listener) {
        m_servicePollStateChangedListeners.remove(listener);
    }

    /** {@inheritDoc} */
    @Override
    public void setInitialPollTime(final Integer polledServiceId, final Date initialPollTime) {
        // Do nothing
    }

    /**
     * <p>setPollerBackEnd</p>
     *
     * @param backEnd a {@link org.opennms.netmgt.poller.remote.PollerBackEnd} object.
     */
    public void setPollerBackEnd(final PollerBackEnd backEnd) {
        m_backEnd = backEnd;
    }

    /**
     * <p>setPollerSettings</p>
     *
     * @param settings a {@link org.opennms.netmgt.poller.remote.PollerSettings} object.
     */
    public void setPollerSettings(final PollerSettings settings) {
        m_pollerSettings = settings;
    }

    /**
     * @param timeAdjustment the timeAdjustment to set
     */
    public void setTimeAdjustment(TimeAdjustment timeAdjustment) {
        m_timeAdjustment = timeAdjustment;
    }

    /**
     * <p>setPollService</p>
     *
     * @param pollService a {@link org.opennms.netmgt.poller.remote.PollService} object.
     */
    public void setPollService(final PollService pollService) {
        m_pollService = pollService;
    }

    private static void assertNotNull(final Object propertyValue, final String propertyName) {
        Assert.state(propertyValue != null, propertyName + " must be set for instances of " + ScanReportPollerFrontEnd.class.getName());
    }

    private void doLoadConfig() {
        Date oldTime = getCurrentConfigTimestamp();

        try {
            m_pollService.setServiceMonitorLocators(m_backEnd.getServiceMonitorLocators(DistributionContext.REMOTE_MONITOR));
            m_pollerConfiguration = retrieveLatestConfiguration();

            synchronized (m_pollState) {

                int i = 0;
                m_pollState.clear();
                for (final PolledService service : getPolledServices()) {
                    // Initialize the monitor for the service
                    m_pollService.initialize(service);
                    m_pollState.put(service.getServiceId(), new ServicePollState(service, i++));
                }
            }

            fireConfigurationChange(oldTime, getCurrentConfigTimestamp());
        } catch (final Exception e) {
            LOG.warn("Unable to get updated poller configuration.", e);
            if (m_pollerConfiguration == null) {
                m_pollerConfiguration = new EmptyPollerConfiguration();
            }
        }
    }

    private PollerConfiguration retrieveLatestConfiguration() {
        PollerConfiguration config = m_backEnd.getPollerConfiguration(getMonitoringSystemId());
        m_timeAdjustment.setMasterTime(config.getServerTime());
        return config;
    }

    private PollStatus doPoll(final Integer polledServiceId) {

        final PolledService polledService = getPolledService(polledServiceId);
        if (polledService == null) {
            return null;
        }
        return m_pollService.poll(polledService);
    }

    private void fireConfigurationChange(final Date oldTime, final Date newTime) {
        final PropertyChangeEvent e = new PropertyChangeEvent(this, "configuration", oldTime, newTime);
        for (final ConfigurationChangedListener listener : m_configChangeListeners) {
            listener.configurationChanged(e);
        }
    }

    private void firePropertyChange(final String propertyName, final Object oldValue, final Object newValue) {
        if (nullSafeEquals(oldValue, newValue)) {
            // no change no event
            return;

        }
        final PropertyChangeEvent event = new PropertyChangeEvent(this, propertyName, oldValue, newValue);

        for (final PropertyChangeListener listener : m_propertyChangeListeners) {
            listener.propertyChange(event);
        }
    }

    private static boolean nullSafeEquals(final Object oldValue, final Object newValue) {
        return (oldValue == newValue ? true : ObjectUtils.nullSafeEquals(oldValue, newValue));
    }

    private void fireServicePollStateChanged(final PolledService polledService, final int index) {
        final ServicePollStateChangedEvent event = new ServicePollStateChangedEvent(polledService, index);

        for (final ServicePollStateChangedListener listener : m_servicePollStateChangedListeners) {
            listener.pollStateChange(event);
        }
    }

    private Date getCurrentConfigTimestamp() {
        return (m_pollerConfiguration == null ? null : m_pollerConfiguration.getConfigurationTimestamp());
    }

    private PolledService getPolledService(final Integer polledServiceId) {
        final ServicePollState servicePollState = getServicePollState(polledServiceId);
        return (servicePollState == null ? null : servicePollState.getPolledService());
    }

    private void setState(final State newState) {
        final boolean started = isStarted();
        final boolean registered = isRegistered();
        m_state = newState;
        firePropertyChange(PollerFrontEndStates.started.toString(), started, isStarted());
        firePropertyChange(PollerFrontEndStates.registered.toString(), registered, isRegistered());

    }

    private void updateServicePollState(final Integer polledServiceId, final PollStatus result) {
        final ServicePollState pollState = getServicePollState(polledServiceId);
        if (pollState == null) {
            return;
        }
        pollState.setLastPoll(result);
        fireServicePollStateChanged(pollState.getPolledService(), pollState.getIndex());
    }

    @Override
    public boolean isExitNecessary() {
        return false;
    }

    @Override
    public void stop() {
        // Do nothing
    }

}
