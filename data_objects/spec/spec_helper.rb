require 'spec'

adapter = (ENV["ADAPTER"] || "sqlite3").dup

require File.dirname(__FILE__) + "/../lib/data_objects"
require File.dirname(__FILE__) + "/../../do_#{adapter}/lib/do_#{adapter}"

adapter_module = adapter.dup.capitalize
$adapter_module = DataObjects.const_get(adapter_module)

$uri = case adapter
when "sqlite3"
  "sqlite3://do_rb.db"
when "mysql"
  "mysql://root@localhost/do_rb//tmp/mysql.sock"
when "postgres"
  "postgres://localhost/do_rb"
end

$escape          = $adapter_module::QUOTE_COLUMN
$escaped_columns = ["id", "int", "time", "bool", "date"].map {|x| "#{$escape}#{x}#{$escape}"}.join(", ")
$quote = quote   = $adapter_module::QUOTE_STRING

begin
  c = $adapter_module::Connection.new($connection_string)
  c.open
  cmd = c.create_command("DROP TABLE table1")
  cmd.execute_non_query rescue nil
  unless adapter == "postgres"
    sql = <<-SQL
    CREATE TABLE table1 (
      `id` serial NOT NULL,
      `int` int(11) default NULL,
      `time` timestamp,
      `bool` tinyint(1) default NULL,
      `date` date default NULL,
      PRIMARY KEY (`id`)
    );
    SQL
  else
    sql = <<-SQL
    CREATE TABLE table1 (
      "id" serial NOT NULL,
      "int" integer default NULL,
      "time" timestamp,
      "bool" boolean default NULL,
      "date" date default NULL,
      PRIMARY KEY ("id")
    );
    SQL
  end
  cmd2 = c.create_command(sql)
  cmd2.execute_non_query
  insert1 = adapter == "postgres" ? 
    "INSERT into table1(#{$escaped_columns}) VALUES(1, NULL, #{quote}#{Time.now.to_s_db}#{quote}, false, #{quote}#{Date.today.to_s}#{quote})" :
    "INSERT into table1(#{$escaped_columns}) VALUES(1, NULL, #{quote}#{Time.now.to_s_db}#{quote}, 0, #{quote}#{Date.today.to_s}#{quote})"
  cmd3 = c.create_command(insert1)
  cmd3.execute_non_query
  insert2 = adapter == "postgres" ?
    "INSERT into table1(#{$escaped_columns}) VALUES(2, 17, #{quote}#{Time.now.to_s_db}#{quote}, true, NULL)" :
    "INSERT into table1(#{$escaped_columns}) VALUES(2, 17, #{quote}#{Time.now.to_s_db}#{quote}, 1, NULL)"
  cmd4 = c.create_command(insert2)    
  cmd4.execute_non_query
ensure
  c.close if defined?(c) && c
end