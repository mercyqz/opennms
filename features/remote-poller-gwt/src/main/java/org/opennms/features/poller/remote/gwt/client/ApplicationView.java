package org.opennms.features.poller.remote.gwt.client;

import org.opennms.features.poller.remote.gwt.client.FilterPanel.StatusSelectionChangedEvent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SplitLayoutPanel;

public class ApplicationView extends Composite {
    
    interface Binder extends UiBinder<DockLayoutPanel, ApplicationView> {}
    
    private static final Binder BINDER = GWT.create(Binder.class);
    
    interface LinkStyles extends CssResource {
        String activeLink();
    }
    
    @UiField
    protected LocationPanel locationPanel;
    

    @UiField
    protected DockLayoutPanel mainPanel;
    @UiField
    protected SplitLayoutPanel splitPanel;
    @UiField
    protected Hyperlink locationLink;
    @UiField
    protected Hyperlink applicationLink;
    @UiField
    protected Label updateTimestamp;
    @UiField
    protected LinkStyles linkStyles;
    @UiField
    protected HorizontalPanel statusesPanel;
    @UiField
    protected CheckBox statusDown;
    @UiField
    protected CheckBox statusDisconnected;
    @UiField
    protected CheckBox statusMarginal;
    @UiField
    protected CheckBox statusUp;
    @UiField
    protected CheckBox statusStopped;
    @UiField
    protected CheckBox statusUnknown;
    
    private final HandlerManager m_eventBus;
    
    
    public ApplicationView(HandlerManager eventBus) {
        m_eventBus = eventBus;
        initWidget(BINDER.createAndBindUi(this));
    }
    
    @UiHandler("statusDown")
    public void onDownClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.DOWN, getStatusDown().getValue()));
    }

    @UiHandler("statusDisconnected")
    public void onDisconnectedClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.DISCONNECTED, getStatusDisconnected().getValue()));
    }

    @UiHandler("statusMarginal")
    public void onMarginalClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.MARGINAL, getStatusMarginal().getValue()));
    }

    @UiHandler("statusUp")
    public void onUpClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.UP, getStatusUp().getValue()));
    }

    @UiHandler("statusStopped")
    public void onStoppedClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.STOPPED, getStatusStopped().getValue()));
    }

    @UiHandler("statusUnknown")
    public void onUnknownClicked(final ClickEvent event) {
        getEventBus().fireEvent(new StatusSelectionChangedEvent(Status.UNKNOWN, getStatusUnknown().getValue()));
    }

    public HandlerManager getEventBus() {
        return m_eventBus;
    }
    
    public DockLayoutPanel getMainPanel() {
        return mainPanel;
    }

    public SplitLayoutPanel getSplitPanel() {
        return splitPanel;
    }

    public HorizontalPanel getStatusesPanel() {
        return statusesPanel;
    }

    public CheckBox getStatusDown() {
        return statusDown;
    }

    public CheckBox getStatusDisconnected() {
        return statusDisconnected;
    }

    public CheckBox getStatusMarginal() {
        return statusMarginal;
    }

    public CheckBox getStatusUp() {
        return statusUp;
    }

    public CheckBox getStatusStopped() {
        return statusStopped;
    }

    public CheckBox getStatusUnknown() {
        return statusUnknown;
    }
    
    public LocationPanel getLocationPanel() {
        return locationPanel;
    }
    
    public Hyperlink getLocationLink() {
        return locationLink;
    }

    public Hyperlink getApplicationLink() {
        return applicationLink;
    }

    public LinkStyles getLinkStyles() {
        return linkStyles;
    }

    public Label getUpdateTimestamp() {
        return updateTimestamp;
    }
    
    Application getPresenter() {
        return null;
    }

    /**
     * <p>onApplicationClick</p>
     *
     * @param event a {@link com.google.gwt.event.dom.client.ClickEvent} object.
     */
    @UiHandler("applicationLink")
    public void onApplicationClick(ClickEvent event) {
        if (getApplicationLink().getStyleName().contains(getLinkStyles().activeLink())) {
            // This link is already selected, do nothing
        } else {
            getPresenter().onApplicationViewSelected();
            getApplicationLink().addStyleName(getLinkStyles().activeLink());
            getLocationLink().removeStyleName(getLinkStyles().activeLink());
            getLocationPanel().showApplicationList();
            getLocationPanel().showApplicationFilters(true);
            getLocationPanel().resizeDockPanel();
        }
    }

    /**
     * <p>onLocationClick</p>
     *
     * @param event a {@link com.google.gwt.event.dom.client.ClickEvent} object.
     */
    @UiHandler("locationLink")
    public void onLocationClick(ClickEvent event) {
        if (getLocationLink().getStyleName().contains(getLinkStyles().activeLink())) {
            // This link is already selected, do nothing
        } else {
            getPresenter().onLocationViewSelected();
            getLocationLink().addStyleName(getLinkStyles().activeLink());
            getApplicationLink().removeStyleName(getLinkStyles().activeLink());
            getLocationPanel().showLocationList();
            getLocationPanel().showApplicationFilters(false);
            getLocationPanel().resizeDockPanel();
        }
    }
}
