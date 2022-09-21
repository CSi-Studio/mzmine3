/*
 * Copyright 2006-2022 The MZmine Development Team
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
 */

package io.github.mzmine.modules.visualization.massvoltammogram;

import javax.swing.JButton;
import javax.swing.JToggleButton;
import org.math.plot.components.PlotToolBar;

/**
 * Class used to extend the PlotToolBar so that the old functions can be accessed from the new
 * toolbar created in the MassvoltammogramTab.
 */
public class ExtendedPlotToolBar extends PlotToolBar {

  final JToggleButton moveButton = buttonCenter;
  final JToggleButton rotateButton = buttonRotate;
  final JButton resetPlotButton = buttonReset;

  public ExtendedPlotToolBar(ExtendedPlot3DPanel plot) {
    super(plot);
  }
}
