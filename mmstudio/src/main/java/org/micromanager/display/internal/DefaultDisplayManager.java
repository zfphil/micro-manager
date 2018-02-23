///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Display implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.display.internal;

import org.micromanager.display.internal.gearmenu.DefaultImageExporter;
import org.micromanager.display.internal.displaywindow.DisplayController;
import org.micromanager.display.internal.event.DataViewerWillCloseEvent;
import org.micromanager.display.internal.event.DataViewerDidBecomeActiveEvent;
import org.micromanager.display.internal.event.DataViewerDidBecomeVisibleEvent;
import org.micromanager.display.internal.event.DataViewerDidBecomeInvisibleEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;
import javax.swing.JOptionPane;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.DataProvider;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.data.internal.PropertyKey;
import org.micromanager.display.ChannelDisplaySettings;
import org.micromanager.display.ComponentDisplaySettings;
import org.micromanager.display.DataViewer;
import org.micromanager.display.DataViewerDelegate;
import org.micromanager.display.DisplayManager;
import org.micromanager.display.DisplaySettings;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.ImageExporter;
import org.micromanager.display.inspector.internal.InspectorCollection;
import org.micromanager.display.inspector.internal.InspectorController;
import org.micromanager.events.DatastoreClosingEvent;
import org.micromanager.events.internal.InternalShutdownCommencingEvent;
import org.micromanager.internal.MMStudio;
import org.micromanager.internal.utils.EventBusExceptionLogger;
import org.micromanager.internal.utils.ReportingUtils;
import org.micromanager.display.DisplayWindowControlsFactory;
import org.micromanager.display.internal.link.LinkManager;
import org.micromanager.display.internal.link.internal.DefaultLinkManager;


// TODO Methods must implement correct threading semantics!
public final class DefaultDisplayManager implements DisplayManager, DataViewerDelegate {
   private static final String[] CLOSE_OPTIONS = new String[] {
         "Cancel", "Prompt for each", "Close without save prompt"};
   private static DefaultDisplayManager staticInstance_;

   private final MMStudio studio_;

   // Map from "managed" datastores to attached displays. Synchronized by
   // monitor on 'this'.
   private final HashMap<Datastore, ArrayList<DisplayWindow>> storeToManagedDisplays_ =
         new HashMap<Datastore, ArrayList<DisplayWindow>>();

   private final DataViewerCollection viewers_ = DataViewerCollection.create();

   private final WeakHashMap<DataViewer, Boolean> haveAutoCreatedInspector_ =
         new WeakHashMap<DataViewer, Boolean>();

   private final InspectorCollection inspectors_ = InspectorCollection.create();

   private final LinkManager linkManager_ = DefaultLinkManager.create();

   private final EventBus eventBus_ = new EventBus(EventBusExceptionLogger.getInstance());

   public DefaultDisplayManager(MMStudio studio) {
      studio_ = studio;
      studio_.events().registerForEvents(this);
      staticInstance_ = this;

      // TODO Leaking this.
      viewers_.registerForEvents(this);
   }

   @Override
   public Datastore show(Image image) {
      Datastore result = studio_.data().createRAMDatastore();
      createDisplay(result);
      try {
         result.putImage(image);
      }
      catch (IOException e) {
         ReportingUtils.logError(e, "Failed to display image");
      }
      return result;
   }

   @Override
   public synchronized List<Datastore> getManagedDatastores() {
      return new ArrayList<Datastore>(storeToManagedDisplays_.keySet());
   }

   @Override
   public synchronized void manage(Datastore store) {
      // Iterate over all display windows, find those associated with this
      // datastore, and manually associate them now.
      ArrayList<DisplayWindow> displays = new ArrayList<DisplayWindow>();
      storeToManagedDisplays_.put(store, displays);
      for (DisplayWindow display : getAllImageWindows()) {
         if (display.getDatastore() == store) {
            displays.add(display);
            display.registerForEvents(this);
            DataViewerDelegate existingDelegate = display.getDelegate();
            if (existingDelegate != null) {
               // TODO This probably needs to be an error
            }
            display.setDelegate(this);
         }
      }
   }

   @Override
   public synchronized boolean getIsManaged(Datastore store) {
      return storeToManagedDisplays_.containsKey(store);
   }

   /**
    * When a Datastore is closed, we need to remove all references to it so
    * it can be garbage-collected.
    * @param event
    */
   @Subscribe
   public void onDatastoreClosed(DatastoreClosingEvent event) {
      // TODO: Displays and "managements" should respond to a willClose event
      // coming from their DataProvider.
      ArrayList<DisplayWindow> displays = null;
      Datastore store = event.getDatastore();
      synchronized (this) {
         if (storeToManagedDisplays_.containsKey(store)) {
            displays = storeToManagedDisplays_.get(store);
            storeToManagedDisplays_.remove(store);
         }
      }
   }

   @Subscribe
   public void onShutdownCommencing(InternalShutdownCommencingEvent event) {
      // If shutdown is already cancelled, don't do anything.
      if (!event.getIsCancelled() && !closeAllDisplayWindows(true)) {
         event.cancelShutdown();
      }
   }

   @Override
   public DisplaySettings getStandardDisplaySettings() {
      return DefaultDisplaySettings.getStandardSettings(null);
   }

   @Override
   public DisplaySettings.DisplaySettingsBuilder getDisplaySettingsBuilder() {
      return new DefaultDisplaySettings.LegacyBuilder();
   }
   
   @Override 
   public DisplaySettings.Builder displaySettingsBuilder() {
      return DefaultDisplaySettings.builder();
   }
   
   @Override
   public ChannelDisplaySettings.Builder channelDisplaySettingsBuilder() {
      return DefaultChannelDisplaySettings.builder();
   }
   
   @Override
   public ComponentDisplaySettings.Builder componentDisplaySettingsBuilder() {
      return DefaultComponentDisplaySettings.builder();
   }

   @Override
   public DisplaySettings.ContrastSettings getContrastSettings(
         Integer contrastMin, Integer contrastMax, Double gamma,
         Boolean isVisible) {
      return new DefaultDisplaySettings.DefaultContrastSettings(
            contrastMin, contrastMax, gamma, isVisible);
   }

   @Override
   public DisplaySettings.ContrastSettings getContrastSettings(
         Integer[] contrastMins, Integer[] contrastMaxes, Double[] gammas,
         Boolean isVisible) {
      return new DefaultDisplaySettings.DefaultContrastSettings(
            contrastMins, contrastMaxes, gammas, isVisible);
   }

   @Override
   public PropertyMap.Builder getPropertyMapBuilder() {
      return PropertyMaps.builder();
   }

   @Override
   public DisplayWindow createDisplay(DataProvider provider) {
      DisplayWindow ret = new DisplayController.Builder(provider).
            linkManager(linkManager_).shouldShow(true).build();
      addViewer(ret);
      return ret;
   }

   @Override
   public DisplayWindow createDisplay(DataProvider provider,
         DisplayWindowControlsFactory factory)
   {
      DisplayWindow ret = new DisplayController.Builder(provider).
            linkManager(linkManager_).shouldShow(true).
            controlsFactory(factory).build();
      addViewer(ret);
      return ret;
   }

   @Override
   public void createInspectorForDataViewer(DataViewer viewer) {
      if (viewer == null || viewer.isClosed()) {
         return;
      }
      InspectorController inspector = InspectorController.create(viewers_);
      inspectors_.addInspector(inspector);
      inspector.attachToFixedDataViewer(viewer);
      inspector.setVisible(true);
   }

   private void createInspectorForFrontmostDataViewer() {
      if (!inspectors_.hasInspectorForFrontmostDataViewer()) {
         InspectorController inspector = InspectorController.create(viewers_);
         inspectors_.addInspector(inspector);
         inspector.attachToFrontmostDataViewer();
         inspector.setVisible(true);
      }
   }

   @Override
   @Deprecated
   public boolean createFirstInspector() {
      createInspectorForFrontmostDataViewer();
      return true;
   }

   @Override
   public void addViewer(DataViewer viewer) {
      viewers_.addDataViewer(viewer);
      if (viewer instanceof DisplayWindow) {
         // TODO DisplayGroupManager.getInstance().addDisplay(viewer);
      }

      DataProvider provider = viewer.getDataProvider();
      if (provider instanceof Datastore) {
         Datastore store = (Datastore) provider;
         synchronized (this) {
            if (getIsManaged(store) && viewer instanceof DisplayWindow) {
               DisplayWindow display = (DisplayWindow) viewer;
               storeToManagedDisplays_.get(store).add(display);
            }
            // TODO Nico added the following, but of course the viewers should
            // not depend on studio. What was it for? --Mark T.
            // studio_.events().registerForEvents(viewer);
         }
      }
   }

   @Override
   public void removeViewer(DataViewer viewer) {
      if (viewer instanceof DisplayWindow) {
         // TODO DisplayGroupManager.getInstance().removeDisplay(viewer);
      }
      viewers_.removeDataViewer(viewer);
   }

   /*
    * NS, 10/2017: Unlike documented in the interface, this only loads a single display
    * Currently, there is only a mechanism to store a single file with one set
    * of DisplaySettings to a datastore location. I can not think of an easy, quick,
    * reliable way to store the displaysettings for multiple viewers.  Moreover,
    * this seems a bit estoric and currently not worth the effort to implement.
    */
   /**
    * @param store Datastore to open displays for
    * @return List with opened DisplayWindows
    * @throws IOException 
    */
   @Override
   public List<DisplayWindow> loadDisplays(Datastore store) throws IOException {
      String path = store.getSavePath();
      ArrayList<DisplayWindow> result = new ArrayList<DisplayWindow>();
      if (path != null) {
         // try to restore display settings
         File displaySettingsFile = new File(store.getSavePath() + File.separator + 
              "DisplaySettings.json");
         DisplaySettings displaySettings = DefaultDisplaySettings.
               fromPropertyMap(PropertyMaps.loadJSON(displaySettingsFile));
         if (displaySettings == null) {
            displaySettings = this.getStandardDisplaySettings();
         }
         // instead of using the createDisplay function, set the correct 
         // displaySettings right away
         DisplayWindow tmp = new DisplayController.Builder(store).
            linkManager(linkManager_).shouldShow(true).initialDisplaySettings(displaySettings).build();
         addViewer(tmp);
         result.add(tmp);
      }
      if (result.isEmpty()) {
         // No path, or no display settings at the path.  Just create a blank
         // new display.
         result.add(createDisplay(store));
      }

      return result;
   }

   @Override
   public synchronized List<DisplayWindow> getDisplays(Datastore store) {
      return new ArrayList<DisplayWindow>(storeToManagedDisplays_.get(store));
   }

   @Override
   public DataViewer getActiveDataViewer() {
      return viewers_.getActiveDataViewer();
   }

   @Override
   @Deprecated
   public DisplayWindow getCurrentWindow() {
      DataViewer viewer = viewers_.getActiveDataViewer();
      if (viewer instanceof DisplayWindow) {
         return (DisplayWindow) viewer;
      }
      return null;
   }

   // TODO Deprecate this and provide better-named methods that get (1) all
   // windows in added order or (2) visible windows in most-recently-activated
   // order. Note that DataViewers are excluded, since a DataViewer may not
   // have a window.
   @Override
   public List<DisplayWindow> getAllImageWindows() {
      List<DataViewer> viewers = viewers_.getAllDataViewers();
      List<DisplayWindow> ret = new ArrayList<DisplayWindow>();
      for (DataViewer viewer : viewers) {
         if (viewer instanceof DisplayWindow) {
            ret.add((DisplayWindow) viewer);
         }
      }
      return ret;
   }

   @Override
   public List<DataViewer> getAllDataViewers() {
      return viewers_.getAllDataViewers();
   }

   @Override
   public boolean closeDisplaysFor(DataProvider provider) {
      for (DisplayWindow display : getAllImageWindows()) {
         if (display.getDataProvider() == provider) {
            if (!display.requestToClose()) {
               // Fail out immediately; don't try to close other displays.
               return false;
            }
         }
      }
      return true;
   }

   // TODO Why do we need both store and display?
   @Override
   public boolean promptToSave(Datastore store, DisplayWindow display) throws IOException {
      String[] options = {"Save", "Discard", "Cancel"};
      int result = JOptionPane.showOptionDialog(display.getWindow(),
            "<html>Do you want to save <i>" + store.getName() + "</i> before closing?",
            "Micro-Manager", JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
      if (result == 2 || result < 0) {
         // User cancelled.
         return false;
      }
      if (result == 0) { // I.e. not the "discard" option
         if (!store.save(display.getWindow())) {
            // Don't close the window, as saving failed.
            return false;
         }
      }
      return true;
   }

   @Override
   public ImageExporter createExporter() {
      return new DefaultImageExporter();
   }

   @Override
   public void promptToCloseWindows() {
      if (getAllImageWindows().isEmpty()) {
         // No open image windows.
         return;
      }
      int result = JOptionPane.showOptionDialog(null,
            "Close all open image windows?", "Micro-Manager",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            CLOSE_OPTIONS, CLOSE_OPTIONS[0]);
      if (result <= 0) { // cancel
         return;
      }
      if (result == 2 && JOptionPane.showConfirmDialog(null,
               "Are you sure you want to close all image windows without prompting to save?",
               "Micro-Manager", JOptionPane.YES_NO_OPTION) == 1) {
         // Close without prompting, but user backed out.
         return;
      }
      studio_.displays().closeAllDisplayWindows(result == 1);
   }

   @Override
   public boolean closeAllDisplayWindows(boolean shouldPromptToSave) {
      for (DisplayWindow display : getAllImageWindows()) {
         if (shouldPromptToSave && !display.requestToClose()) {
            // User cancelled closing.
            return false;
         }
         else if (!shouldPromptToSave) {
            // Force display closed.
            display.close();
         }
      }
      return true;
   }

   private void removeDisplay(DisplayWindow display) {
      Datastore store = display.getDatastore();
      synchronized (this) {
         storeToManagedDisplays_.get(store).remove(display);
         if (display.getDelegate() == this) {
            display.setDelegate(null);
         }
      }
   }

   @Subscribe
   public void onEvent(DataViewerDidBecomeVisibleEvent e) {
      // If the viewer has been shown for the first time, we ensure that an
      // inspector is open, set to display info on the frontmost viewer.
      Boolean haveCreatedInspector =
            haveAutoCreatedInspector_.get(e.getDataViewer());
      if (haveCreatedInspector == null || !haveCreatedInspector) {
         createInspectorForFrontmostDataViewer();
         haveAutoCreatedInspector_.put(e.getDataViewer(), true);
      }
      eventBus_.post(e);
   }

   @Subscribe
   public void onEvent(DataViewerDidBecomeInvisibleEvent e) {
      eventBus_.post(e);
   }

   @Subscribe
   public void onEvent(DataViewerDidBecomeActiveEvent e) {
      eventBus_.post(e);
   }

   @Subscribe
   public void onEvent(DataViewerWillCloseEvent e) {
      eventBus_.post(e);
   }

   @Deprecated
   public static DefaultDisplayManager getInstance() {
      return staticInstance_;
   }

   @Override
   public void registerForEvents(Object recipient) {
      eventBus_.register(recipient);
   }

   @Override
   public void unregisterForEvents(Object recipient) {
      eventBus_.unregister(recipient);
   }

   @Override
   public boolean dataViewerShouldClose(DataViewer viewer) {
      DataProvider provider = viewer.getDataProvider();
      List<DisplayWindow> displays;
      if (!(provider instanceof Datastore)) {
         return true;
      }
      Datastore store = (Datastore) provider;
      synchronized (this) {
         if (!storeToManagedDisplays_.containsKey(store)) {
            // This should never happen.
            ReportingUtils.logError("Received request to close a display that is not associated with a managed datastore.");
            return true;
         }
         displays = getDisplays(store);

         if (viewer instanceof DisplayWindow) {
            DisplayWindow window = (DisplayWindow) viewer;
            if (!displays.contains(window)) {
               // This should also never happen.
               ReportingUtils.logError("Was notified of a request to close a display that we didn't know was associated with datastore " + provider);
            }

            if (displays.size() > 1) {
               // Not last display, so OK to remove 
               removeDisplay(window);
               return true;
            }
            // Last display; check for saving now.
            if (store.getSavePath() != null) {
               // No problem with saving.
               removeDisplay(window);
               return true;
            }
            // Prompt the user to save their data.
            try {
               if (promptToSave(store, window)) {
                  window.close();
                  store.freeze();
                  // This will invoke our onDatastoreClosed() method.
                  store.close();
                  return true;
               }
            } catch (IOException ioe) {
               ReportingUtils.showError(ioe, "Failed to save:");
               return false;
            }
         }
         return false;
      }
   }
}