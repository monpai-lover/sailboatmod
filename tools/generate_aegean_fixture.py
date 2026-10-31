#!/usr/bin/env python3
"""Rebuild the GSHHS coastline-only regression fixture. Requires basemap, basemap-data, shapely, numpy.
No server connection or user world data is read. GSHHS derivative: LGPL-3.0, see fixture README.
"""
import pathlib, numpy as np
root = pathlib.Path(__file__).resolve().parents[1] / 'src/test/resources/route-terrain'
root.mkdir(parents=True, exist_ok=True)
# Real GSHHS intermediate coastline polygons, not a made-up open ocean.
from mpl_toolkits.basemap import Basemap
from shapely.geometry import Polygon,box,Point
from shapely.ops import unary_union
from shapely import contains_xy
import math,gzip
m=Basemap(projection='cyl',llcrnrlon=22,llcrnrlat=34,urcrnrlon=30.3,urcrnrlat=41.5,resolution='i',area_thresh=0)
polys=[Polygon(p.get_coords()) for p in m.landpolygons]
land=unary_union([p.buffer(0) for p in polys])
R=6378137/100
# Tellus-style spherical Mercator horizontal projection, test scale 100 m / block.
def fromgeo(lon,lat):return R*math.radians(lon),-R*math.log(math.tan(math.pi/4+math.radians(lat)/2))
def togeo(x,z):return math.degrees(x/R),math.degrees(2*math.atan(math.exp(-z/R))-math.pi/2)
x0,z0=map(lambda v:math.floor(v/4)*4,fromgeo(22,41.5)); x1,z1=map(lambda v:math.ceil(v/4)*4,fromgeo(30.3,34))
step=4;w=(x1-x0)//step+1;h=(z1-z0)//step+1
xs=x0+np.arange(w)*step;zv=z0+np.arange(h)*step;lons=np.degrees(xs/R)
lat=np.degrees(2*np.arctan(np.exp(-zv/R))-np.pi/2)
mask=np.empty((h,w),dtype=bool)
for k in range(0,h,128): mask[k:k+128]=~contains_xy(land,lons[None,:],lat[k:k+128,None])
header=f'{w} {h} {x0} {z0} {step}\n'
# RLE rows keeps small text diff, no binary fixture dependency.
rows=[]
for row in mask:
 transitions=np.r_[0,np.flatnonzero(row[1:]!=row[:-1])+1,len(row)];lengths=np.diff(transitions)
 rows.append(('W ' if row[0] else 'L ')+' '.join(map(str,lengths)))
(root/'aegean-gshhs-i.rle').write_text(header+'\n'.join(rows)+'\n')
# endpoints in navigable marine water north of Crete and off Istanbul, not city centres on land.
for name,lon,lt in [('crete',25.15,35.37),('istanbul',28.97,40.99)]:
 x,z=fromgeo(lon,lt); x=round(x);z=round(z)
 print(name,(x,z),lon,lt,'isWater',not land.contains(Point(lon,lt)))
print('coast raster',w,h,'water%',mask.mean(), 'file size', (root/'aegean-gshhs-i.rle').stat().st_size)

