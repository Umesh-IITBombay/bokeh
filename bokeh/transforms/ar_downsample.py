from __future__ import print_function
from ..plotting import curdoc
from ..plot_object import PlotObject
from ..objects import  ServerDataSource,  Glyph, Range1d
from bokeh.properties import (Instance, Any)
import logging
logger = logging.getLogger(__file__)

try:
  import abstract_rendering.numeric as numeric
  import abstract_rendering.general as general
  import abstract_rendering.infos as infos
  import abstract_rendering.core as ar
  import abstract_rendering.glyphset as glyphset
except:
  print("\n\n-----------------------------------------------------------------------")
  print("Error loading the abstract rendering package.\n")
  print("To use the ar_downsample module, you must install the abstract rendering framework.")
  print("This can be cloned from github at https://github.com/JosephCottam/AbstractRendering")
  print("Install from the ./python directory with 'python setup.py install' (may require admin privledges)")
  print("Questions and feedback can be directed to Joseph Cottam (jcottam@indiana.edu)")
  print("-----------------------------------------------------------------------\n\n")
#  raise


class Proxy(PlotObject):
  """Proxy objects stand in for the abstract rendering (AR) configuration classes.
     Basically, the AR implementation doesn't rely on Bokeh, so
     it doesn't know about the properties BUT the Bokeh needs be able to
     construct/modify/inspect AR configurations.  Proxy classes hold the relevant
     parameters for constructing AR classes in a way that Bokeh can inspect.
     Furthermore, 'reify' produces an AR class from a proxy instance.
  """
  def reify(self, **kwargs):
    raise NotImplementedError("Unipmlemented")


#### Aggregators -------------
class Sum(Proxy): 
  def reify(self, **kwargs):
    return numeric.Sum()

class Count(Proxy): 
  def reify(self, **kwargs):
    return numeric.Count()

### Infos ---------
class Const(Proxy):
  val = Any()
  def reify(self, **kwargs):
    return infos.const(self.val)

#### Transfers ---------

#Out types to support ---
# image -- grid of values
# rgb_image -- grid of colors
# poly_line -- multi-segment lines (for ISO contours...)

class Transfer(Proxy):
  def __add__(self, other): 
    return Seq(first=self, second=other)

class Seq(Transfer):
  first = Instance(Transfer)
  second = Instance(Transfer)

  def reify(self, **kwargs):
    return self.first.reify(**kwargs) + self.second.reify(**kwargs)

  def __getattr__(self, name):
    if (name == 'out'):
      self.out = self.second.out
      return self.out
    else:
      raise AttributeError(name)



class Id(Transfer): 
  out = "image"
  def reify(self, **kwargs):
    return general.Id()

class Interpolate(Transfer):
  out = "image"
  high = Any ##TODO: Restrict to numbers... 
  low = Any 
  def reify(self, **kwargs):
    return numeric.Interpolate(self.low, self.high)

class Sqrt(Transfer):
  out = "image"
  def reify(self, **kwargs):
    return numeric.Sqrt()

class Cuberoot(Transfer):
  out = "image"
  def reify(self, **kwargs):
    return numeric.Cuberoot()

#TODO: Pass the 'rend' defintiion through (minus the data_source references), unpack in 'downsample' instead of here...
#TODO: Move reserve control up here or palette control down.  Probably related to refactoring palette into a model-backed type
def source(plot, agg=Count(), info=Const(val=1), shader=Id(), remove_original=True, palette=["Spectral-11"], **kwargs):
  #Acquire information from renderer...
  rend = [r for r in plot.renderers if isinstance(r, Glyph)][0]
  datasource = rend.server_data_source
  kwargs['data_url'] = datasource.data_url
  kwargs['owner_username'] = datasource.owner_username
  
  spec = rend.vm_serialize()['glyphspec']

  if (shader.out == "image"): 
    kwargs['data'] = {'image': [],
                      'x': [0], 
                      'y': [0],
                      'global_x_range' : [0, 10],
                      'global_y_range' : [0, 10],
                      'global_offset_x' : [0],
                      'global_offset_y' : [0],
                      'dw' : [10], 
                      'dh' : [10], 
                      'palette': palette
                    }
  else: 
    raise ValueError("Can only work with image-shaders...for now")
  
  ##Remove the base plot (if requested)
  if remove_original and plot in curdoc()._plotcontext.children: 
    curdoc()._plotcontext.children.remove(plot)  

  kwargs['transform'] = {'resample':"abstract rendering", 'agg':agg, 'info':info, 'shader':shader, 'glyphspec': spec}
  return ServerDataSource(**kwargs)

def mapping(source):
  """Setup property mapping dictionary from source to output glyph type.
  """

  trans = source.transform
  out = trans['shader'].out

  if (out == 'image'):
    keys = source.data.keys() 
    m = dict(zip(keys, keys))
    x_range = Range1d(start=0, end=500)
    y_range = Range1d(start=0, end=500)
    m['x_range'] = x_range
    m['y_range'] = y_range
    return m
  else:
    raise ValueError("Only handling image type in property mapping...")

def downsample(data, transform, plot_state):
  screen_size = [span(plot_state['screen_x']),
                 span(plot_state['screen_y'])]

  scale_x = span(plot_state['data_x'])/float(span(plot_state['screen_x']))
  scale_y = span(plot_state['data_y'])/float(span(plot_state['screen_y']))
  
  #How big would a full plot of the data be at the current resolution?
  plot_size = [screen_size[0] / scale_x,  screen_size[1] / scale_y]
  
  glyphspec = transform['glyphspec']
  xcol = glyphspec['x']['field']
  ycol = glyphspec['y']['field']
  size = glyphspec['size']['default'] ##TODO: Will not work for data-derived sizes...

  ###Translate the resample paramteres to server-side rendering....
  ###TODO: Should probably handle this type-based-unpacking server_backend so downsamples get a consistent view of the data
  if type(data) is dict:
    xcol = data[xcol]
    ycol = data[ycol]
  else:
    table = data.select(columns=[xcol, ycol])
    xcol = table[xcol]
    ycol = table[ycol]
  
  shaper = _shaper(glyphspec['type'], size)
  glyphs = glyphset.Glyphset([xcol, ycol], ar.EmptyList(), shaper, colMajor=True)
  bounds = glyphs.bounds()
  ivt = ar.zoom_fit(plot_size, bounds, balanced=False)  

  image = ar.render(glyphs, 
                    transform['info'].reify(), 
                    transform['agg'].reify(), 
                    transform['shader'].reify(), 
                    plot_size, ivt)
  
  return {'image': [image],
          'x': [0],
          'y': [0],
          'dw': [image.shape[0]],
          'dh': [image.shape[1]],
  }


def span(r):
    return r.end - r.start

def _shaper(code, size):
  code = code.lower()
  if not code == 'square':
    raise ValueError("Only recognizing 'square' received " + code)
  
  tox = glyphset.idx(0)
  toy = glyphset.idx(1)
  sizer = glyphset.const(size)
  return glyphset.ToRect(tox, toy, sizer, sizer)
