package org.jzy3d.chart;

import org.jzy3d.chart.factories.IChartFactory;
import org.jzy3d.maths.Dimension;
import org.jzy3d.plot2d.primitive.AWTColorbarImageGenerator;
import org.jzy3d.plot3d.primitives.Drawable;
import org.jzy3d.plot3d.primitives.axis.layout.AxisLayout;
import org.jzy3d.plot3d.rendering.canvas.Quality;
import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend;
import org.jzy3d.plot3d.rendering.view.AWTRenderer2d;
import org.jzy3d.plot3d.rendering.view.AWTView;

public class AWTChart extends Chart {
  public AWTChart(IChartFactory components, Quality quality) {
    super(components, quality);
  }

  protected AWTChart() {
    super();
  }

  public void addRenderer(AWTRenderer2d renderer2d) {
    getAWTView().addRenderer2d(renderer2d);
  }

  public void removeRenderer(AWTRenderer2d renderer2d) {
    getAWTView().removeRenderer2d(renderer2d);
  }

  public AWTView getAWTView() {
    return (AWTView) view;
  }

  public AWTColorbarLegend colorbar(Drawable drawable) {
    return colorbar(drawable, null, getView().getAxis().getLayout());
  }

  public AWTColorbarLegend colorbar(Drawable drawable, AxisLayout layout) {
    return colorbar(drawable, null, layout);
  }

  public AWTColorbarLegend colorbar(Drawable drawable, Dimension minDimension, AxisLayout layout) {
    AWTColorbarLegend colorbar = new AWTColorbarLegend(drawable, layout);
    
    if(minDimension!=null)
      colorbar.setMinimumDimension(minDimension);
    
    drawable.setLegend(colorbar);
    return colorbar;
  }
}
