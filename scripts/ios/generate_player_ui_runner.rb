#!/usr/bin/env ruby
# Standalone runner keeps the installed QA app and its bundled cores intact.
require 'xcodeproj'
root=File.expand_path('../..', __dir__)
path=root+'/ios/build/PlayerUIRunner.xcodeproj'
p=Xcodeproj::Project.new(path)
t=p.new_target(:ui_test_bundle,'PlayerUIRunner',:ios,'17.0')
t.add_file_references(Dir[root+'/ios/AppUITests/{PlayerExplorationTests,ExpansionControlsTests}.swift'].map { |f| p.main_group.new_file(f) })
t.build_configurations.each do |c|
 c.build_settings['PRODUCT_BUNDLE_IDENTIFIER']='io.github.mattsays.rommio.player-ui-runner'
 c.build_settings['GENERATE_INFOPLIST_FILE']='YES'
 c.build_settings['CODE_SIGNING_ALLOWED']='NO'
 c.build_settings['SWIFT_VERSION']='6.0'
 c.build_settings['SUPPORTED_PLATFORMS']='iphonesimulator'
 c.build_settings['TARGETED_DEVICE_FAMILY']='1,2'
end
p.save
s=Xcodeproj::XCScheme.new;s.add_build_target(t);s.add_test_target(t);s.save_as(path,'PlayerUIRunner')
