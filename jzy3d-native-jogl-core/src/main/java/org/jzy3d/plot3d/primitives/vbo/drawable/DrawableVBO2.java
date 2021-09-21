package org.jzy3d.plot3d.primitives.vbo.drawable;


import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;
import org.jzy3d.colors.colormaps.IColorMap;
import org.jzy3d.io.IGLLoader;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.maths.Normal;
import org.jzy3d.painters.IPainter;
import org.jzy3d.painters.NativeDesktopPainter;
import org.jzy3d.plot3d.primitives.IGLBindedResource;
import org.jzy3d.plot3d.primitives.Wireframeable;
import org.jzy3d.plot3d.rendering.lights.Light;
import org.jzy3d.plot3d.rendering.scene.Graph;
import org.jzy3d.plot3d.transform.Transform;
import com.google.common.collect.ArrayListMultimap;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2GL3;
import com.jogamp.opengl.fixedfunc.GLPointerFunc;

/**
 * A {@link DrawableVBO2} is able to efficiently draw a large collection of geometries.
 * 
 * 
 * <p>
 * <b>Effect of repeated vertices</b><br>
 * 
 * Repeated vertices make all vertice normal being processed with the only three vertices of a
 * triangle. A collection of neighbour triangles hence have normals producing sharp light reaction
 * as bellow.
 * 
 * <img src="doc-files/REPEATED_VERTEX_AND_NORMALS.png"/>
 * 
 * </p>
 * 
 * <p>
 * <b>Shared vertices between triangles based on element index</b><br>
 * 
 * Sharing vertices among triangles avoid repeating data, and also allows knowing all surrounding
 * triangles to a point, hence allowing to compute a normal based on the mean of all triangles
 * normal. This produce a smooth light reaction at the triangle edges.
 * 
 * <img src="doc-files/SHARED_VERTEX_AVERAGED_NORMALS.png"/>
 * </p>
 * 
 * <p>
 * <b>Not processing normals in java</b><br>
 * 
 * Is faster and yield to this light reaction.
 * 
 * <img src="doc-files/SHARED_VERTEX_NO_NORMAL.png"/>
 * </p>
 * 
 * <p>
 * <b>Using a colormap</b><br>
 * 
 * <img src="doc-files/COLORMAP.png"/>
 * </p>
 * 
 * <p>
 * <b>Initializing DrawableVBO2</b></br>
 * 
 * <pre>
 * <code>
 * AWTChartFactory f = new AWTChartFactory();
 * Chart chart = f.newChart(Quality.Intermediate());
 *
 * int dimensions = TestMesh.DIMENSIONS;
 * double[] vertices = TestMesh.makeArray4();
 * int[] elements = TestMesh.makeElementArray4();
 * int nVertices = TestMesh.nVertices(vertices);
 *
 * DrawableVBO2 vbo = new DrawableVBO2(vertices, dimensions, elements, null);
 * chart.add(vbo);
 * chart.open();
 * </code>
 * </pre>
 * </p>
 * 
 * @see {@link DrawableVBO2} constructor documentation for details.
 * 
 * 
 */
public class DrawableVBO2 extends Wireframeable implements IGLBindedResource {
  public static boolean COMPUTE_NORMALS_IN_JAVA = true;
  public static boolean PRIMITIVE_RESTART = true;
  public static int PRIMITIVE_RESTART_VALUE = 0xffff;

  protected IGLLoader<DrawableVBO2> loader;

  protected boolean hasNormalInVertexArray = false;


  protected IntBuffer elements;
  protected FloatBuffer vertices;
  protected FloatBuffer normals;
  protected FloatBuffer colors;

  protected int firstCoordOffset = 0;

  /** Byte shift between two vertices in the vertex buffer. */
  protected int vertexOffset;
  /** Byte shift between to access a vertice normal in the vertex buffer. */
  protected int normalOffset;
  /** Number of element (geometries) in the element buffer. */
  protected int elementSize;

  protected int[] colorArrayIds = new int[1];
  protected int[] vertexArrayIds = new int[1];
  protected int[] normalArrayIds = new int[1];
  protected int[] elementArrayIds = new int[1];

  protected boolean hasMountedOnce = false;

  protected static int QUAD_SIZE = 4;
  protected static int TRIANGLE_SIZE = 3;

  protected static int GEOMETRY_SIZE = TRIANGLE_SIZE; // triangle

  protected static int VERTEX_SIZE = 3; // x,y,z

  protected int geometrySize = -1;
  protected int colorChannels = 3; // r,g,b

  protected int glGeometryType; // GL_TRIANGLES, etc
  
  protected boolean hasColorBuffer = false;
  protected Color color = new Color(1f, 0f, 1f, 0.75f);

  /**
   * Should be true AND the element array provided to be able to process averaged normal. If any of
   * these two conditions is not fullfilled, then one normal per point is computed.
   */
  protected NormalMode normalMode = NormalMode.PER_VERTEX;



  public enum NormalMode{
    SHARED,
    PER_VERTEX
  }
  

  /**
   * Initialize a VBO object with arrays with the following content.
   * 
   * 
   * @param points contains an array of vertices
   *        <code>[x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, ..]</code>
   * @param pointDimensions indicate the number of dimension in the array for each vertex, as the
   *        input array may contain extra dimensions for each vertex, e.g. [x1, y1, z1, m1, x2, y2,
   *        z2, m2, ...]
   * @param elements contains reference to points that may be used in several elements. E.g. the
   *        array <code>[1, 3, 2, 4,
   *        3, 2, ...]</code> indicates that the two first triangles are sharing vertices 3 and 2.
   *        This assume the user of this class is aware that this class will build triangles made of
   *        3 vertices each. It may be null, in that case the <code>points</code> array will be
   *        drawn as a sequence of vertices defining elements, without sharing vertices between
   *        elements.
   * @param colormap defines how to color vertices. It may be null, in that case the object is
   *        colored by {@link #setColor(Color)} adn shaded by {@link Light}
   */
  public DrawableVBO2(double[] points, int pointDimensions, int[] elements, IColorMap colormap) {
    this(makeLoader(points, pointDimensions, elements, GEOMETRY_SIZE, colormap, null, NormalMode.SHARED));
  }

  public DrawableVBO2(double[] points, int pointDimensions, int[] elements, IColorMap colormap, NormalMode normalMode) {
    this(makeLoader(points, pointDimensions, elements, GEOMETRY_SIZE, colormap, null, normalMode));
  }

  /**
   * Initialize a VBO object with arrays with no colormap. The object has a uniform color given by
   * {@link #setColor(Color)}.
   * 
   * Providing an element array allows defining which points form a triangle without having to
   * repeat these points. When such constructor is used, shared vertices allow computing shared
   * normals for each point, hence each point normal is computed as a mean of the normals of all
   * triangles that share this point. The result is smoother light transition between polygons,
   * which differ from the light transition of repeated-vertex schemes as processed when the element
   * array is null (or when invoking {@link DrawableVBO2(double[], int)}
   * 
   * <img src="doc-files/SHARED_VERTEX_AVERAGED_NORMALS.png"/>
   * 
   * @see other constructor for detailed arguments.
   * 
   */
  public DrawableVBO2(double[] points, int pointDimensions, int[] elements) {
    this(makeLoader(points, pointDimensions, elements, GEOMETRY_SIZE, null, null, NormalMode.PER_VERTEX));
  }

  public DrawableVBO2(double[] points, int pointDimensions, int[] elements, int elementSize,
      float[] colors) {
    this(makeLoader(points, pointDimensions, elements, elementSize, null, colors, NormalMode.PER_VERTEX));
  }

  /**
   * Initialize a VBO object with arrays with no colormap and no vertex sharing scheme. The object
   * has a uniform color given by {@link #setColor(Color)}.
   * 
   * When using a {@link Light}, the object will have edges looking sharp as shown on the picture
   * below. One can obtain smoother edges by avoiding vertex repetitions and instead define an
   * element array indicating which unique vertex should be used in each triangle. See
   * {@link DrawableVBO2(double[], int, int[]).
   * 
   * <img src="doc-files/REPEATED_VERTEX_AND_NORMALS.png"/>
   * 
   * @see other constructor for detailed arguments.
   * 
   */
  public DrawableVBO2(double[] points, int pointDimensions) {
    this(makeLoader(points, pointDimensions, null, GEOMETRY_SIZE, null, null, NormalMode.PER_VERTEX));
  }

  /**
   * Initialize a VBO object with arrays with a colormap but no vertex sharing scheme.
   * 
   * <img src="doc-files/COLORMAP.png"/>
   * 
   * @see other constructor for detailed arguments.
   * 
   */
  public DrawableVBO2(double[] points, int pointDimensions, IColorMap colormap) {
    this(makeLoader(points, pointDimensions, null, GEOMETRY_SIZE, colormap, null, NormalMode.PER_VERTEX));
  }

  /**
   * Initialize a VBO object with a customizable loader.
   */
  public DrawableVBO2(IGLLoader<DrawableVBO2> loader) {
    this.loader = loader;
  }

  /* ***************************************************************** */
  /* *********************** LOAD DRAWABLE *************************** */
  /* ***************************************************************** */

  /**
   * Invoke the loader. This is called by {@link Graph} when the application initialize and a
   * {@link GL} context is available for feeding the GPU with arrays.
   */
  @Override
  public void mount(IPainter painter) {
    try {
      loader.load(painter, this);
      hasMountedOnce = true;
    } catch (Exception e) {
      e.printStackTrace();
      Logger.getLogger(DrawableVBO2.class).error(e, e);
    }
  }

  @Override
  public boolean hasMountedOnce() {
    return hasMountedOnce;
  }

  /**
   * Return a loader for this VBO that is invoked upon {@link #mount(IPainter)}, meaning after the
   * application has started.
   * 
   * We observed that when no normal is processed at all, there is still an automatic processing of
   * light "somehow". This is enabled by toggling {#link {@link #COMPUTE_NORMALS_IN_JAVA} to false.
   * In that case, the result looks like :
   * 
   * <img src="doc-files/SHARED_VERTEX_NO_NORMAL.png"/>
   * 
   * Concerning colors, the loader can be given
   * <ul>
   * <li>A non null colormap with null colors array
   * <li>A non null color array with null colormap
   * <li>None of the above, in that case, the color will uniform to the whole drawable
   * <li>If both colormap and colors are given, an exception is thrown.
   * </ul>
   * 
   * @see {@link DrawableVBO2} constructor for argument description.
   */
  protected static IGLLoader<DrawableVBO2> makeLoader(double[] points, int pointDimensions,
      int[] geometries, int geometrySize, IColorMap colormap, float[] coloring, NormalMode normalMode) {
    return new VBOBufferLoader(points, pointDimensions, geometries, geometrySize, colormap, coloring, normalMode);
  }

  /**
   * Configure a VBO with vertices, colors, and indices describing vertex references for building
   * triangles.
   * 
   * This is supposed to be called by the {@link IGLLoader} when {@link #mount(IPainter)} is
   * invoked.
   * 
   * @see the constructor documentation for argument description.
   */
  public void setData(IPainter painter, IntBuffer elements, FloatBuffer vertices,
      FloatBuffer normals, FloatBuffer colors, BoundingBox3d bounds) {
    this.vertices = vertices;
    this.elements = elements;
    this.normals = normals;
    this.colors = colors;

    if (hasNormalInVertexArray) {
      this.vertexOffset = (VERTEX_SIZE * 2) * Buffers.SIZEOF_FLOAT; // (coord+normal)
    } else {
      this.vertexOffset = VERTEX_SIZE * Buffers.SIZEOF_FLOAT; // (coord only)
    }
    this.normalOffset = VERTEX_SIZE * Buffers.SIZEOF_FLOAT;


    GL gl = getGL(painter);
    
    /*if(PRIMITIVE_RESTART) {
      if (!gl.isGL2()) {
        throw new RuntimeException("Need a GL2 instance");
      }

      GL2 gl2 = gl.getGL2();
      // https://stackoverflow.com/questions/26944959/opengl-separating-polygons-inside-vbo
      gl2.glPrimitiveRestartIndex(PRIMITIVE_RESTART_VALUE);
      gl2.glEnable(GL2.GL_PRIMITIVE_RESTART);
      //gl2.glEnable(GL2.GL_PRIMITIVE_RESTART_FIXED_INDEX);
    }*/


    // -----------------------------------
    // Vertices

    int vertexSize = vertices.capacity() * Buffers.SIZEOF_FLOAT;
    gl.glGenBuffers(1, vertexArrayIds, 0);
    gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexArrayIds[0]);
    gl.glBufferData(GL.GL_ARRAY_BUFFER, vertexSize, vertices, GL.GL_STATIC_DRAW);


    // -----------------------------------
    // Normals

    if (normals != null) {
      int normalSize = normals.capacity() * Buffers.SIZEOF_FLOAT;
      gl.glGenBuffers(1, normalArrayIds, 0);
      gl.glBindBuffer(GL.GL_ARRAY_BUFFER, normalArrayIds[0]);
      gl.glBufferData(GL.GL_ARRAY_BUFFER, normalSize, normals, GL.GL_STATIC_DRAW);
    }

    // -----------------------------------
    // Color

    hasColorBuffer = colors != null;

    if (hasColorBuffer) {
      int colorSize = colors.capacity() * Buffers.SIZEOF_FLOAT;
      gl.glGenBuffers(1, colorArrayIds, 0);
      gl.glBindBuffer(GL.GL_ARRAY_BUFFER, colorArrayIds[0]);
      gl.glBufferData(GL.GL_ARRAY_BUFFER, colorSize, colors, GL.GL_STATIC_DRAW);
    }


    // -----------------------------------
    // Elements

    if (elements != null) {
      this.elementSize = elements.capacity();

      int indexSize = elements.capacity() * Buffers.SIZEOF_INT;

      gl.glGenBuffers(1, elementArrayIds, 0);
      gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, elementArrayIds[0]);
      gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, indexSize, elements, GL.GL_STATIC_DRAW);

    }


    // -----------------------------------
    // Bounds

    bbox = bounds;
  }


  /* ***************************************************************** */
  /* ********************** RENDER DRAWABLE ************************** */
  /* ***************************************************************** */

  @Override
  public void draw(IPainter painter) {
    if (hasMountedOnce) {
      doTransform(painter);
      doDrawElements(painter);
      doDrawBoundsIfDisplayed(painter);
    }
  }

  /**
   * Perform rendering of this VBO.
   * 
   * @param painter holds a GL instance to invoke the GPU.
   * 
   * @see http://www.opengl-tutorial.org/intermediate-tutorials/tutorial-9-vbo-indexing/
   * @see https://github.com/opengl-tutorials/ogl/blob/master/tutorial09_vbo_indexing/tutorial09.cpp
   */
  protected void doDrawElements(IPainter painter) {
    GL gl = getGL(painter);

    if (!gl.isGL2()) {
      throw new RuntimeException("Need a GL2 instance");
    }

    GL2 gl2 = gl.getGL2();
    
    if(PRIMITIVE_RESTART) {
      // https://www.khronos.org/opengl/wiki/Vertex_Rendering#Primitive_Restart
      // https://stackoverflow.com/questions/4386861/opengl-jogl-multiple-triangle-fans-in-a-vertex-array
      // https://stackoverflow.com/questions/26944959/opengl-separating-polygons-inside-vbo
      gl2.glEnable(GL2.GL_PRIMITIVE_RESTART);
      gl2.glPrimitiveRestartIndex(PRIMITIVE_RESTART_VALUE);
      
      
      //gl2.glEnable(GL2.GL_PRIMITIVE_RESTART_FIXED_INDEX);
    }


    // -----------------------------------
    // Prepare buffers

    gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexArrayIds[0]);
    gl2.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, elementArrayIds[0]);

    gl2.glVertexPointer(VERTEX_SIZE, GL.GL_FLOAT, vertexOffset, firstCoordOffset);
    gl2.glEnableClientState(GLPointerFunc.GL_VERTEX_ARRAY);

    if (normalArrayIds[0] != 0) {
      // Experimental : try providing explicit normals
      gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, normalArrayIds[0]);
      gl2.glNormalPointer(GL.GL_FLOAT, normalOffset, firstCoordOffset);
      gl2.glEnableClientState(GLPointerFunc.GL_NORMAL_ARRAY);
    } else {
      // "Automatic normals", not really sure they are correct
      // invoking gl.glDisable(GL2.GL_AUTO_NORMAL) does not disable them surprisingly
      gl2.glNormalPointer(GL.GL_FLOAT, vertexOffset, normalOffset);
      gl2.glEnableClientState(GLPointerFunc.GL_NORMAL_ARRAY);
    }


    if (hasColorBuffer) {
      gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, colorArrayIds[0]);
      gl2.glColorPointer(colorChannels, GL.GL_FLOAT, colorChannels * Buffers.SIZEOF_FLOAT,
          firstCoordOffset);
      gl2.glEnableClientState(GL2.GL_COLOR_ARRAY);
    } else {
      gl2.glColor4f(color.r, color.g, color.b, color.a);
    }


    // -----------------------------------
    // Offset filling

    if (isPolygonOffsetFillEnable())
      polygonOffsetFillEnable(painter); // typo fixed in 2.0.1
    else
      polygonOffsetFillDisable(painter);


    // -----------------------------------
    // Draw faces

    if (isFaceDisplayed()) {
      gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2GL3.GL_FILL);

      if (elementSize > 0) {
        gl2.glDrawElements(glGeometryType, elementSize, GL.GL_UNSIGNED_INT, firstCoordOffset);
      } else {
        // in case indices where not defined
        gl2.glDrawArrays(glGeometryType, 0, vertices.capacity());
      }
    }


    // -----------------------------------
    // Draw wireframe

    if (isWireframeDisplayed()) {
      // disable coloring to use single color
      if (hasColorBuffer) {
        gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
      }

      Color c = getWireframeColor();

      gl2.glColor4f(c.r, c.g, c.b, c.a);
      gl2.glLineWidth(getWireframeWidth());

      gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2.GL_LINE);

      if (elementSize > 0) {
        gl2.glDrawElements(glGeometryType, elementSize, GL.GL_UNSIGNED_INT, firstCoordOffset);
      } else {
        // in case indices where not defined
        gl2.glDrawArrays(glGeometryType, 0, vertices.capacity());
      }
    }


    // -----------------------------------
    // Disable

    gl2.glDisableClientState(GLPointerFunc.GL_VERTEX_ARRAY);
    gl2.glDisableClientState(GLPointerFunc.GL_NORMAL_ARRAY);

    // disable coloring if it was not done before
    if (hasColorBuffer && !isWireframeDisplayed()) {
      gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
    }
  }

  /* ***************************************************************** */
  /* ************************** PROPERTIES *************************** */
  /* ***************************************************************** */

  public boolean isHasColorBuffer() {
    return hasColorBuffer;
  }

  public void setHasColorBuffer(boolean hasColorBuffer) {
    this.hasColorBuffer = hasColorBuffer;
  }

  public Color getColor() {
    return color;
  }

  public void setColor(Color color) {
    this.color = color;
  }

  public boolean hasNormalInVertexArray() {
    return hasNormalInVertexArray;
  }

  public void setHasNormalInVertexArray(boolean hasNormal) {
    this.hasNormalInVertexArray = hasNormal;
  }

  public int getColorChannels() {
    return colorChannels;
  }

  public void setColorChannels(int colorChannels) {
    this.colorChannels = colorChannels;
  }

  @Override
  public void applyGeometryTransform(Transform transform) {
    Logger.getLogger(DrawableVBO2.class).warn("not implemented");
  }

  @Override
  public void updateBounds() { // requires smart reload
    Logger.getLogger(DrawableVBO2.class).warn("not implemented");
  }

  protected GL getGL(IPainter painter) {
    return ((NativeDesktopPainter) painter).getGL();
  }


  /* ***************************************************************** */
  /* **************************** BUFFERS **************************** */
  /* ***************************************************************** */

  public IntBuffer getElements() {
    return elements;
  }

  public FloatBuffer getVertices() {
    return vertices;
  }

  public FloatBuffer getNormals() {
    return normals;
  }

  public FloatBuffer getColors() {
    return colors;
  }

  public int[] getColorArrayIds() {
    return colorArrayIds;
  }

  public int[] getVertexArrayIds() {
    return vertexArrayIds;
  }

  public int[] getNormalArrayIds() {
    return normalArrayIds;
  }

  public int[] getElementArrayIds() {
    return elementArrayIds;
  }
}
