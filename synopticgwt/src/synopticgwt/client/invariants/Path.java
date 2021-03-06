package synopticgwt.client.invariants;

import java.io.Serializable;

import com.google.gwt.core.client.JavaScriptObject;

import synopticgwt.client.util.MouseHover;
import synopticgwt.client.util.Paper;

/**
 * Java wrapper for a Raphael path object
 * 
 */

public class Path implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Whether or not the starting point of the path is higher than the ending
     * point.
     */
    private boolean pathSrcAtTop;
    /** Wrapper for canvas this path is drawn on */
    private Paper paper;
    /** Raphael path */
    private JavaScriptObject path;

    /**
     * Creates a new path from (x1, y1) to (x2, y2)
     * 
     * <pre>
     * NOTE: paper's (0,0) is top
     * left, and we assert that the path is drawn from left to right (i.e., x1 < x2).
     * </pre>
     * 
     * @param x1
     *            origin x coordinate
     * @param y1
     *            origin y coordinate
     * @param x2
     *            terminal x coordinate
     * @param y2
     *            terminal y coordinate
     * @param paper
     *            Raphael canvas wrapper
     */
    public Path(double x1, double y1, double x2, double y2, Paper paper) {
        this.pathSrcAtTop = y1 < y2;
        this.paper = paper;
        this.path = constructPath(x1, y1, x2, y2, paper.getPaper());
    }

    /**
     * Creates JS path object on paper from (x1, y1) to x2, y2)
     * 
     * @param originX
     *            origin x coordinate
     * @param originY
     *            origin y coordinate
     * @param terminalX
     *            terminal x coordinate
     * @param terminalY
     *            terminal y coordinate
     * @param canvas
     *            unwrapped raphael canvas
     * @return unwrapped raphael path
     */
    private native JavaScriptObject constructPath(double originX,
            double originY, double terminalX, double terminalY,
            JavaScriptObject canvas) /*-{
		var path = canvas.path("M" + originX + " " + originY + "L" + terminalX
				+ " " + terminalY);
		return path;
    }-*/;

    /**
     * Makes the path visible on paper
     */
    public native void show() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.show();
    }-*/;

    /**
     * Makes the path invisible on paper
     */
    public native void hide() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.hide();
    }-*/;

    /**
     * Changes the paths's color and stroke width to color and width
     * 
     * @param color
     * @param width
     */
    public native void setStroke(String color, int width) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.attr({
			stroke : color,
			'stroke-width' : width
		});
    }-*/;

    /**
     * Registers hover mouseover with the Raphael path
     * 
     * @param hover
     *            object with java level mouseover function
     */
    public native void setMouseover(MouseHover hover) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.mouseover(function(hoverable) {
			return function(e) {
				hoverable.@synopticgwt.client.util.MouseHover::mouseover()();
			};
		}(hover));
    }-*/;

    /**
     * Registers hover mouseout with the Raphael path
     * 
     * @param hover
     *            object with java level mouseout function
     */
    public native void setMouseout(MouseHover hover) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.mouseout(function(hoverable) {
			return function(e) {
				hoverable.@synopticgwt.client.util.MouseHover::mouseout()();
			};
		}(hover));
    }-*/;

    /**
     * Translates path by dx and dy
     * 
     * @param dx
     *            horizontal shift
     * @param dy
     *            vertical shift
     */
    public native void translate(double dx, double dy) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.translate(dx, dy);
    }-*/;

    public void scale(double targetWidth, double targetHeight) {
        scaleJS(1.0, 1.0);
        double sx = targetWidth / getBBoxWidth();
        double sy = targetHeight / getBBoxHeight();
        scaleJS(sx, sy);
    }

    /**
     * Scales path by cx and cy
     */
    private native void scaleJS(double sx, double sy) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		var bBox = path.getBBox();
		path.scale(sx, sy);
    }-*/;

    /**
     * @return X value of the path's bounding box
     */
    public native float getBBoxX() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		var BBox = path.getBBox();
		return BBox.x;
    }-*/;

    /**
     * @return X value of the center of the path's bounding box
     */
    public double getCenterX() {
        return getBBoxX() + getBBoxWidth() / 2;
    }

    /**
     * @return y value of the path's bounding box
     */
    public native float getBBoxY() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		var BBox = path.getBBox();
		return BBox.y;
    }-*/;

    /**
     * @return Y value of the center of the path's bounding box
     */
    public double getCenterY() {
        return getBBoxY() + getBBoxHeight() / 2;
    }

    /**
     * @return height of path's bounding box
     */
    public native float getBBoxHeight() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		var BBox = path.getBBox();
		return BBox.height;
    }-*/;

    /**
     * @return width of path's bounding box
     */
    public native float getBBoxWidth() /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		var BBox = path.getBBox();
		return BBox.width;
    }-*/;

    public double getX1() {
        return getBBoxX();
    }

    public double getY1() {
        return pathSrcAtTop ? getBBoxY() : getBBoxY() + getBBoxHeight();
    }

    public double getX2() {
        return getBBoxX() + getBBoxWidth();
    }

    public double getY2() {
        return pathSrcAtTop ? getBBoxY() + getBBoxHeight() : getBBoxY();
    }

    public boolean pathSrcIsAtTop() {
        return pathSrcAtTop;
    }

    public native void rotate(double r, double cx, double cy) /*-{
		var path = this.@synopticgwt.client.invariants.Path::path;
		path.rotate(r, cx, cy);
    }-*/;

}
