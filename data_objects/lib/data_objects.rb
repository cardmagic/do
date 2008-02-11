require 'date'
require 'logger'
require File.dirname(__FILE__) + '/connection_pool'

# Thanks http://www.rubyweeklynews.org/20051120
class DateTime
 def to_time
   Time.mktime(year, mon, day, hour, min, sec)
 end
 
 def to_date
   Date.new(year, mon, day)
 end
end

class Time
  def to_datetime
    DateTime.civil(year, mon, day, hour, min, sec)
  end
  
  def to_s_db
    strftime("%Y-%m-%d %X")
  end
end