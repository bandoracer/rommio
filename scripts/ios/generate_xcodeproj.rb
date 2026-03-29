#!/usr/bin/env ruby

require 'fileutils'
require 'xcodeproj'

repo_root = File.expand_path('../..', __dir__)
ios_root = File.join(repo_root, 'ios')
project_path = File.join(ios_root, 'Rommio.xcodeproj')
workspace_path = File.join(ios_root, 'Rommio.xcworkspace')

FileUtils.rm_rf(project_path)
FileUtils.rm_rf(workspace_path)

project = Xcodeproj::Project.new(project_path)
project.root_object.attributes['LastUpgradeCheck'] = '1600'
project.root_object.attributes['TargetAttributes'] ||= {}

app_group = project.main_group.new_group('App', 'App')
app_source = app_group.new_file('RommioApp.swift')
app_source.path = 'RommioApp.swift'
resources_group = app_group.new_group('Resources', 'Resources')
resource_files = Dir[File.join(ios_root, 'App', 'Resources', '**', '*')].sort.filter_map do |path|
    next if File.directory?(path)

    relative_path = path.delete_prefix(File.join(ios_root, 'App', 'Resources') + '/')
    file = resources_group.new_file(relative_path)
    file.path = relative_path
    file
end

ui_tests_group = project.main_group.new_group('AppUITests', 'AppUITests')
ui_test_sources = Dir[File.join(ios_root, 'AppUITests', '*.swift')].sort.map do |path|
    file = ui_tests_group.new_file(File.basename(path))
    file.path = File.basename(path)
    file
end

app_target = project.new_target(:application, 'Rommio', :ios, '17.0')
app_target.add_file_references([app_source])
resource_files.each do |resource|
    app_target.resources_build_phase.add_file_reference(resource, true)
end
ui_test_target = project.new_target(:ui_test_bundle, 'RommioAppUITests', :ios, '17.0')
ui_test_target.add_file_references(ui_test_sources)
ui_test_target.add_dependency(app_target)

[app_target, ui_test_target].each do |target|
    target.frameworks_build_phase.files.each do |build_file|
        file_ref = build_file.file_ref
        next unless file_ref&.display_name == 'Foundation.framework'

        build_file.remove_from_project
    end
end

project.files.each do |file_ref|
    next unless file_ref.display_name == 'Foundation.framework'

    file_ref.remove_from_project
end

project.build_configurations.each do |config|
    config.build_settings['SWIFT_VERSION'] = '6.0'
    config.build_settings.delete('SDKROOT')
end

app_target.build_configurations.each do |config|
    config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'io.github.mattsays.rommio.ios'
    config.build_settings['PRODUCT_NAME'] = '$(TARGET_NAME)'
    config.build_settings['SWIFT_VERSION'] = '6.0'
    config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
    config.build_settings['INFOPLIST_KEY_CFBundleDisplayName'] = 'Rommio'
    config.build_settings['INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents'] = 'YES'
    config.build_settings['INFOPLIST_KEY_UILaunchScreen_Generation'] = 'YES'
    config.build_settings['INFOPLIST_KEY_LSApplicationCategoryType'] = 'public.app-category.entertainment'
    config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '17.0'
    config.build_settings['TARGETED_DEVICE_FAMILY'] = '1,2'
    config.build_settings['SUPPORTED_PLATFORMS'] = 'iphoneos iphonesimulator'
    config.build_settings['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks']
    config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
    config.build_settings['CODE_SIGNING_REQUIRED'] = 'NO'
    config.build_settings.delete('SDKROOT')
end

ui_test_target.build_configurations.each do |config|
    config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'io.github.mattsays.rommio.ios.ui-tests'
    config.build_settings['PRODUCT_NAME'] = '$(TARGET_NAME)'
    config.build_settings['SWIFT_VERSION'] = '6.0'
    config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
    config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '17.0'
    config.build_settings['TARGETED_DEVICE_FAMILY'] = '1,2'
    config.build_settings['SUPPORTED_PLATFORMS'] = 'iphoneos iphonesimulator'
    config.build_settings['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks']
    config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
    config.build_settings['CODE_SIGNING_REQUIRED'] = 'NO'
    config.build_settings['TEST_TARGET_NAME'] = 'Rommio'
    config.build_settings.delete('SDKROOT')
end

project.root_object.attributes['TargetAttributes'][app_target.uuid] = {
    'CreatedOnToolsVersion' => '16.0'
}
project.root_object.attributes['TargetAttributes'][ui_test_target.uuid] = {
    'CreatedOnToolsVersion' => '16.0',
    'TestTargetID' => app_target.uuid
}

package_reference = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
package_reference.path = '.'
package_reference.relative_path = '.'
project.root_object.package_references << package_reference

grdb_package_reference = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
grdb_package_reference.repositoryURL = 'https://github.com/groue/GRDB.swift.git'
grdb_package_reference.requirement = {
    'kind' => 'upToNextMajorVersion',
    'minimumVersion' => '7.0.0'
}
project.root_object.package_references << grdb_package_reference

%w[
    RommioContract
    RommioFoundation
    RommioPlayerBridge
    RommioPlayerKit
    RommioUI
].each do |product_name|
    dependency = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
    dependency.product_name = product_name
    dependency.package = package_reference
    app_target.package_product_dependencies << dependency

    build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
    build_file.product_ref = dependency
    app_target.frameworks_build_phase.files << build_file
end

grdb_dependency = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
grdb_dependency.product_name = 'GRDB'
grdb_dependency.package = grdb_package_reference
app_target.package_product_dependencies << grdb_dependency

grdb_build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
grdb_build_file.product_ref = grdb_dependency
app_target.frameworks_build_phase.files << grdb_build_file

scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(app_target, ui_test_target, launch_target: true)
scheme.save_as(project_path, 'Rommio', true)

project.save

FileUtils.mkdir_p(workspace_path)
File.write(
  File.join(workspace_path, 'contents.xcworkspacedata'),
  <<~XML
    <?xml version="1.0" encoding="UTF-8"?>
    <Workspace version="1.0">
      <FileRef location="group:Rommio.xcodeproj"></FileRef>
    </Workspace>
  XML
)

puts "Generated #{project_path}"
