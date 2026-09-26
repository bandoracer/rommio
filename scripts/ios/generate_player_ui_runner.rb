#!/usr/bin/env ruby
# Standalone UI test runner for the installed Rommio app (simulator or device); it
# keeps the installed app and its bundled cores intact.
#
#   generate_player_ui_runner.rb [--actions FILE] [--theme aurora|riso] [--scheme light|dark]
#                                [--query TEXT] [--iterations N] [--controls] [--team TEAM_ID]
#
# --actions embeds an action list (see ios/AppUITests/UIActionRunner.swift) in the
# test bundle as rommio-ui-actions.json, so a pass runs on a device without the
# host's /tmp. --theme/--scheme/--query/--iterations go into rommio-ui-config.json
# for the navigation pass (NavigationPassTests), which launches the app with
# -appearance.theme/-appearance.scheme. --controls opts the controls-harness tests in
# (PlayerHarnessButtonTests, PlayerStickControlsTests; see run_controls_pass.sh). --team (or
# ROMMIO_DEVELOPMENT_TEAM) signs the runner for a device; simulator builds are never signed.
require 'fileutils'
require 'json'
require 'optparse'
require 'xcodeproj'

options = {}
OptionParser.new do |parser|
  parser.on('--actions FILE') { |value| options[:actions] = value }
  parser.on('--theme THEME') { |value| options[:theme] = value }
  parser.on('--scheme SCHEME') { |value| options[:scheme] = value }
  parser.on('--query TEXT') { |value| options[:query] = value }
  parser.on('--iterations N') { |value| options[:iterations] = value }
  parser.on('--capture-prefix PREFIX') { |value| options[:capturePrefix] = value }
  parser.on('--team TEAM') { |value| options[:team] = value }
  parser.on('--controls') { options[:controls] = '1' }
end.parse!

root = File.expand_path('../..', __dir__)
path = root + '/ios/build/PlayerUIRunner.xcodeproj'
resources = root + '/ios/build/player-ui-runner-resources'
FileUtils.rm_rf(path)
FileUtils.rm_rf(resources)
FileUtils.mkdir_p(resources)

p = Xcodeproj::Project.new(path)
t = p.new_target(:ui_test_bundle, 'PlayerUIRunner', :ios, '17.0')
t.add_file_references(Dir[root + '/ios/AppUITests/{UIActionRunner,PlayerExplorationTests,ExpansionControlsTests,NavigationPassTests,PlayerTouchControlsTests,PlayerStickControlsTests,PlayerHarnessButtonTests}.swift'].sort.map { |f| p.main_group.new_file(f) })

bundled = []
if options[:actions]
  actions = JSON.parse(File.read(options[:actions]))
  raise 'The actions file must be a JSON array of objects' unless actions.is_a?(Array)
  File.write(resources + '/rommio-ui-actions.json', JSON.pretty_generate(actions))
  bundled << 'rommio-ui-actions.json'
end
config = options.slice(:theme, :scheme, :query, :iterations, :capturePrefix, :controls).transform_keys(&:to_s).compact
unless config.empty?
  File.write(resources + '/rommio-ui-config.json', JSON.pretty_generate(config))
  bundled << 'rommio-ui-config.json'
end
bundled.each do |name|
  t.add_resources([p.main_group.new_file(resources + '/' + name)])
end

team = options[:team] || ENV['ROMMIO_DEVELOPMENT_TEAM']
t.build_configurations.each do |c|
  c.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'io.github.mattsays.rommio.player-ui-runner'
  c.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  c.build_settings['SWIFT_VERSION'] = '6.0'
  c.build_settings['SUPPORTED_PLATFORMS'] = 'iphoneos iphonesimulator'
  c.build_settings['TARGETED_DEVICE_FAMILY'] = '1,2'
  c.build_settings['CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]'] = 'NO'
  if team && !team.empty?
    c.build_settings['CODE_SIGN_STYLE'] = 'Automatic'
    c.build_settings['DEVELOPMENT_TEAM'] = team
  else
    c.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
  end
end
p.save
s = Xcodeproj::XCScheme.new
s.add_build_target(t)
s.add_test_target(t)
s.save_as(path, 'PlayerUIRunner')
puts "Generated #{path}#{bundled.empty? ? '' : " with #{bundled.join(', ')}"}"
