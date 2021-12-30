/*
 * Copyright 2006-2021 The MZmine Development Team
 *
 * This file is part of MZmine.
 *
 * MZmine is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with MZmine; if not,
 * write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package io.github.mzmine.modules.visualization.spectra.msn_tree;

import io.github.mzmine.datamodel.DataPoint;
import io.github.mzmine.datamodel.MassSpectrumType;
import io.github.mzmine.datamodel.PrecursorIonTree;
import io.github.mzmine.datamodel.PrecursorIonTreeNode;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.impl.SimpleDataPoint;
import io.github.mzmine.datamodel.msms.MsMsInfo;
import io.github.mzmine.gui.chartbasics.chartgroups.ChartGroup;
import io.github.mzmine.gui.chartbasics.gui.wrapper.ChartViewWrapper;
import io.github.mzmine.gui.mainwindow.SimpleTab;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.dataprocessing.featdet_massdetection.exactmass.ExactMassDetector;
import io.github.mzmine.modules.visualization.spectra.simplespectra.SpectraPlot;
import io.github.mzmine.modules.visualization.spectra.simplespectra.datasets.DataPointsDataSet;
import io.github.mzmine.modules.visualization.spectra.simplespectra.datasets.RelativeOption;
import io.github.mzmine.modules.visualization.spectra.simplespectra.renderers.ArrowRenderer;
import io.github.mzmine.modules.visualization.spectra.simplespectra.renderers.PeakRenderer;
import io.github.mzmine.util.color.SimpleColorPalette;
import io.github.mzmine.util.javafx.FxColorUtil;
import io.github.mzmine.util.scans.ScanUtils;
import java.awt.Color;
import java.awt.Shape;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import org.jetbrains.annotations.NotNull;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.data.xy.AbstractXYDataset;
import org.jfree.data.xy.XYDataset;

/**
 * @author Robin Schmid (https://github.com/robinschmid)
 */
public class MSnTreeTab extends SimpleTab {

  private final AtomicLong currentThread = new AtomicLong(0L);
  private final TreeView<PrecursorIonTreeNode> treeView;
  private final GridPane spectraPane;
  private final Label legendEnergies;
  private final CheckBox cbRelative;
  private final CheckBox cbDenoise;
  private final List<SpectraPlot> spectraPlots = new ArrayList<>(1);
  private int lastSelectedItem = -1;
  private ChartGroup chartGroup;
  private PrecursorIonTreeNode currentRoot = null;

  public MSnTreeTab() {
    super("MSn Tree", true, false);

    BorderPane main = new BorderPane();

    // add tree to the left
    // buttons over tree
    HBox buttons = new HBox(5, // add buttons
        createButton("Expand", e -> expandTreeView(true)),
        createButton("Collapse", e -> expandTreeView(false)));

    treeView = new TreeView<>();
    ScrollPane treeScroll = new ScrollPane(treeView);
    //    treeScroll.setHbarPolicy(ScrollBarPolicy.NEVER);
    treeScroll.setFitToHeight(true);
    treeScroll.setFitToWidth(true);

    TreeItem<PrecursorIonTreeNode> root = new TreeItem<>();
    root.setExpanded(true);
    treeView.setRoot(root);
    treeView.setShowRoot(false);
    treeView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    treeView.getSelectionModel().selectedItemProperty()
        .addListener(((observable, oldValue, newValue) -> showSpectra(newValue)));

    BorderPane left = new BorderPane();
    left.setTop(buttons);
    left.setCenter(treeScroll);

    // create spectra grid
    spectraPane = new GridPane();
    final ColumnConstraints col = new ColumnConstraints(200, 350, -1, Priority.ALWAYS, HPos.LEFT,
        true);
    spectraPane.getColumnConstraints().add(col);
    spectraPane.setGridLinesVisible(true);
    final RowConstraints rowConstraints = new RowConstraints(200, 350, -1, Priority.ALWAYS,
        VPos.CENTER, true);
    spectraPane.getRowConstraints().add(rowConstraints);
    // create first plot and initialize group for zooming etc
    chartGroup = new ChartGroup(false, false, true, false);
    chartGroup.setShowCrosshair(true, false);
    final SpectraPlot plot = new SpectraPlot();
    spectraPlots.add(plot);
    chartGroup.add(new ChartViewWrapper(plot));
    spectraPane.add(new BorderPane(spectraPlots.get(0)), 0, 0);

    ScrollPane scrollSpectra = new ScrollPane(new BorderPane(spectraPane));
    scrollSpectra.setFitToHeight(true);
    scrollSpectra.setFitToWidth(true);
    scrollSpectra.setVbarPolicy(ScrollBarPolicy.ALWAYS);

    BorderPane center = new BorderPane(scrollSpectra);

    // add menu to spectra
    HBox spectraMenu = new HBox(4);
    center.setTop(spectraMenu);

    legendEnergies = new Label("");
    cbRelative = new CheckBox("Relative");
    cbRelative.selectedProperty().addListener((o, ov, nv) -> changeRelative());

    cbDenoise = new CheckBox("Denoise");
    cbDenoise.selectedProperty().addListener((o, ov, nv) -> updateCurrentSpectra());

    // menu
    spectraMenu.getChildren().addAll( // menu
        createButton("Auto range", this::autoRange), //
        cbRelative, cbDenoise, legendEnergies);

    SplitPane splitPane = new SplitPane(left, center);
    splitPane.setDividerPositions(0.22);
    main.setCenter(splitPane);

    // add main to tab
    main.getStyleClass().add("region-match-chart-bg");
    this.setContent(main);

    main.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.DOWN) {
        nextPrecursor();
        main.requestFocus();
        e.consume();
      } else if (e.getCode() == KeyCode.UP) {
        previousPrecursor();
        main.requestFocus();
        e.consume();
      }
    });
  }

  private void changeRelative() {
    if (currentRoot != null) {
        final boolean normalize = cbRelative.isSelected();
      for (var p : spectraPlots) {
        for (int i = 0; i < p.getXYPlot().getDatasetCount(); i++) {
          final XYDataset data = p.getXYPlot().getDataset(i);
          if (data instanceof RelativeOption op) {
            op.setRelative(normalize);
          }
        }

        if (p.getXYPlot().getRangeAxis() instanceof NumberAxis va) {
          if (normalize) {
            va.setNumberFormatOverride(new DecimalFormat("0.#"));
          } else {
            va.setNumberFormatOverride(MZmineCore.getConfiguration().getIntensityFormat());
          }
        }

        p.getChart().fireChartChanged();
      }
      chartGroup.recalcMaxRanges();
      chartGroup.resetRangeZoom();
    }
  }

  private void updateCurrentSpectra() {
    if (currentRoot != null) {
      showSpectra(currentRoot);
    }
  }

  private void autoRange(ActionEvent actionEvent) {
    if (chartGroup != null) {
      chartGroup.resetZoom();
    }
  }

  private Button createButton(String title, EventHandler<ActionEvent> action) {
    final Button button = new Button(title);
    button.setOnAction(action);
    return button;
  }

  /**
   * Set raw data file and update tree
   *
   * @param raw update all views to this raw file
   */
  public synchronized void setRawDataFile(RawDataFile raw) {
    lastSelectedItem = -1;
    treeView.getRoot().getChildren().clear();
    //    spectraPane.getChildren().clear();

    // track current thread
    final long current = currentThread.incrementAndGet();
    Thread thread = new Thread(() -> {
      // run on different thread
      final List<PrecursorIonTree> trees = ScanUtils.getMSnFragmentTrees(raw);
      MZmineCore.runLater(() -> {
        if (current == currentThread.get()) {

          treeView.getRoot().getChildren()
              .addAll(trees.stream().map(t -> createTreeItem(t.getRoot())).toList());

          expandTreeView(treeView.getRoot(), false);
        }
      });
    });
    thread.start();
  }

  private TreeItem<PrecursorIonTreeNode> createTreeItem(PrecursorIonTreeNode node) {
    final var item = new TreeItem<>(node);
    item.getChildren()
        .addAll(node.getChildPrecursors().stream().map(this::createTreeItem).toList());
    return item;
  }

  private void showSpectra(TreeItem<PrecursorIonTreeNode> node) {
    showSpectra(node == null ? null : node.getValue());
  }

  public void showSpectra(PrecursorIonTreeNode any) {
    spectraPane.getChildren().clear();
    spectraPane.getRowConstraints().clear();
    spectraPlots.forEach(SpectraPlot::removeAllDataSets);
    if (any == null) {
      return;
    }
    // add spectra
    PrecursorIonTreeNode prevRoot = currentRoot;
    currentRoot = any.getRoot();
    boolean rootHasChanged = !Objects.equals(prevRoot, currentRoot);

    // colors
    final SimpleColorPalette colors = MZmineCore.getConfiguration().getDefaultColorPalette();

    SpectraPlot previousPlot = null;
    // distribute collision energies in three categories low, med, high
    final List<Float> collisionEnergies = currentRoot.getAllFragmentScans().stream()
        .map(Scan::getMsMsInfo).filter(Objects::nonNull).map(MsMsInfo::getActivationEnergy)
        .filter(Objects::nonNull).distinct().sorted().toList();

    float minEnergy = 0f;
    float maxEnergy = 0f;
    float medEnergy = 0f;
    if (!collisionEnergies.isEmpty()) {
      minEnergy = collisionEnergies.get(0);
      maxEnergy = collisionEnergies.get(collisionEnergies.size() - 1);
      medEnergy = collisionEnergies.get(collisionEnergies.size() / 2);
      // set legend
      if (minEnergy != maxEnergy) {
        if (minEnergy != medEnergy && maxEnergy != medEnergy) {
          legendEnergies.setText(
              String.format("Activation: ▽≈%.0f △≈%.0f ◇≈%.0f", minEnergy, medEnergy, maxEnergy));
        } else {
          legendEnergies.setText(String.format("Activation: ▽≈%.0f ◇≈%.0f", minEnergy, maxEnergy));
        }
      } else {
        legendEnergies.setText(String.format("Activation: ◇≈%.0f", maxEnergy));
      }
    }
    // relative intensities? and denoise?
    final boolean normalizeIntensities = cbRelative.isSelected();
    final boolean denoise = cbDenoise.isSelected();

    List<PrecursorIonTreeNode> levelPrecursors = List.of(currentRoot);
    int levelFromRoot = 0;
    do {
      // create one spectra plot for each MS level
      if (levelFromRoot >= spectraPlots.size()) {
        final SpectraPlot plot = new SpectraPlot();
        spectraPlots.add(plot);
        chartGroup.add(new ChartViewWrapper(plot));
      }
      SpectraPlot spectraPlot = spectraPlots.get(levelFromRoot);

      if (spectraPlot.getXYPlot().getRangeAxis() instanceof NumberAxis va) {
        if (normalizeIntensities) {
          va.setNumberFormatOverride(new DecimalFormat("0.#"));
        } else {
          va.setNumberFormatOverride(MZmineCore.getConfiguration().getIntensityFormat());
        }
      }
      // create combined dataset for each MS level
      int c = 0;
      for (PrecursorIonTreeNode precursor : levelPrecursors) {
        final Color color = FxColorUtil.fxColorToAWT(colors.get(c % colors.size()));
        final List<Scan> fragmentScans = precursor.getFragmentScans();
        for (final Scan scan : fragmentScans) {
          AbstractXYDataset data = ensureCentroidDataset(normalizeIntensities, denoise, scan);
          // add peak renderer to show centroids
          spectraPlot.addDataSet(data, color, false, false);
          spectraPlot.getXYPlot()
              .setRenderer(spectraPlot.getNumOfDataSets() - 1, new PeakRenderer(color, false));

          // add shapes
          spectraPlot.addDataSet(data, color, false, null, false);
          final Shape shape = getActivationEnergyShape(scan.getMsMsInfo().getActivationEnergy(),
              minEnergy, medEnergy, maxEnergy);
          spectraPlot.getXYPlot()
              .setRenderer(spectraPlot.getNumOfDataSets() - 1, new ArrowRenderer(shape, color));
        }

        // add precursor markers for each different precursor only once
        spectraPlot.addPrecursorMarkers(precursor.getFragmentScans().get(0), color, 0.25f);
        c++;
      }
      // hide x axis
      if (previousPlot != null) {
        previousPlot.getXYPlot().getDomainAxis().setVisible(false);
      }
      // add
      spectraPlot.getXYPlot().getDomainAxis().setVisible(true);
      spectraPane.getRowConstraints()
          .add(new RowConstraints(200, 250, -1, Priority.ALWAYS, VPos.CENTER, true));
      spectraPane.add(new BorderPane(spectraPlot), 0, levelFromRoot);
      previousPlot = spectraPlot;
      // next level
      levelFromRoot++;
      levelPrecursors = currentRoot.getPrecursors(levelFromRoot);
    } while (!levelPrecursors.isEmpty());

    if (rootHasChanged) {
      chartGroup.applyAutoRange(true);
    }
  }

  @NotNull
  private AbstractXYDataset ensureCentroidDataset(boolean normalizeIntensities, boolean denoise,
      Scan scan) {
    final double[][] masses;
    if (scan.getMassList() != null) {
      masses = new double[][]{scan.getMassList().getMzValues(new double[0]),
          scan.getMassList().getIntensityValues(new double[0])};
    } else if (!MassSpectrumType.PROFILE.equals(scan.getSpectrumType())) {
      masses = new double[][]{scan.getMzValues(new double[0]),
          scan.getIntensityValues(new double[0])};
    } else {
      // profile data run mass detection
      masses = ExactMassDetector.getMassValues(scan, 0);
    }
    List<DataPoint> dps = new ArrayList<>();
    if (denoise) {
      final double[] sortedIntensities = Arrays.stream(masses[1]).filter(v -> v > 0).sorted()
          .toArray();
      double min = sortedIntensities[0];
      // remove everything <2xmin
      for (int i = 0; i < masses[0].length; i++) {
        if (masses[1][i] > min * 2.5d) {
          dps.add(new SimpleDataPoint(masses[0][i], masses[1][i]));
        }
      }
    } else {
      // filter zeros
      for (int i = 0; i < masses[0].length; i++) {
        if (masses[1][i] > 0) {
          dps.add(new SimpleDataPoint(masses[0][i], masses[1][i]));
        }
      }
    }
    return new DataPointsDataSet("", dps.toArray(DataPoint[]::new), normalizeIntensities);
  }

  /**
   * Three groups of activation energies close to min, median, max
   *
   * @param ae current activation energy
   * @return a shape from the {@link ArrowRenderer}
   */
  private Shape getActivationEnergyShape(Float ae, float minEnergy, float medEnergy,
      float maxEnergy) {
    if (ae == null) {
      return ArrowRenderer.diamond;
    }
    final float med = Math.abs(medEnergy - ae);
    return ae - minEnergy < med ? ArrowRenderer.downArrow
        : (med < maxEnergy - ae ? ArrowRenderer.upArrow : ArrowRenderer.diamond);
  }

  private void expandTreeView(boolean expanded) {
    expandTreeView(treeView.getRoot(), expanded);
  }

  private void expandTreeView(TreeItem<?> item, boolean expanded) {
    if (item != null && !item.isLeaf()) {
      item.setExpanded(expanded);
      for (TreeItem<?> child : item.getChildren()) {
        expandTreeView(child, expanded);
      }
    }
    treeView.getRoot().setExpanded(true);
  }

  public void previousPrecursor() {
    if (lastSelectedItem > 0) {
      lastSelectedItem--;
      treeView.getSelectionModel().select(getMS2Nodes().get(lastSelectedItem));
    }
  }

  private ObservableList<TreeItem<PrecursorIonTreeNode>> getMS2Nodes() {
    return treeView.getRoot().getChildren();
  }

  public void nextPrecursor() {
    if (lastSelectedItem + 1 < getMS2Nodes().size()) {
      lastSelectedItem++;
      treeView.getSelectionModel().select(treeView.getRoot().getChildren().get(lastSelectedItem));
    }
  }


  @Override
  public void onRawDataFileSelectionChanged(Collection<? extends RawDataFile> rawDataFiles) {
    if (rawDataFiles != null && rawDataFiles.size() > 0) {
      setRawDataFile(rawDataFiles.stream().findFirst().get());
    }
  }
}